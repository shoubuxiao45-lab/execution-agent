package com.executionagent.app.data.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.executionagent.app.domain.ai.AiConfig
import com.executionagent.app.domain.ai.AiConfigStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureAiConfigStore @Inject constructor(@ApplicationContext context: Context) : AiConfigStore {
    private val prefs = context.getSharedPreferences("ai_config", Context.MODE_PRIVATE)
    private val mutableNotice = MutableStateFlow<String?>(null)
    override val notice = mutableNotice.asStateFlow()
    private val mutableConfig = MutableStateFlow(read())
    override val config = mutableConfig.asStateFlow()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("execution_agent_ai", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("execution_agent_ai", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun read(): AiConfig = try {
        val stored = prefs.getString("AI_API_KEY", null)
        val secret = if (stored == null) "" else {
            val parts = stored.split(":")
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))) }
                .doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }
        AiConfig(prefs.getString("AI_BASE_URL", null) ?: "https://api.moonshot.cn/v1/", secret,
            prefs.getString("AI_MODEL", null) ?: "kimi-k2.6").validated()
    } catch (_: Exception) {
        mutableNotice.value = "AI 设置或密钥无法读取，已使用离线模式。请重新输入或清除密钥。"
        AiConfig()
    }
    // KTX edit(commit=true) discards commit's Boolean; retain it to report disk failures.
    @android.annotation.SuppressLint("UseKtx")
    override suspend fun save(config: AiConfig) = withContext(Dispatchers.IO) {
        config.validated()
        val editor = prefs.edit().putString("AI_BASE_URL", config.baseUrl).putString("AI_MODEL", config.model)
        if (config.isMock) editor.remove("AI_API_KEY") else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val encoded = Base64.encodeToString(cipher.doFinal(config.apiKey.toByteArray()), Base64.NO_WRAP)
            editor.putString("AI_API_KEY", "${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:$encoded")
        }
        check(editor.commit()) { "AI 设置保存失败" }
        mutableConfig.value = config
        mutableNotice.value = null
    }
}
