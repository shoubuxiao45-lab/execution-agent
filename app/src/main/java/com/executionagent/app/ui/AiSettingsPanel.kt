package com.executionagent.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.executionagent.app.domain.ai.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class AiSettingsViewModel @Inject constructor(private val store: AiConfigStore) : ViewModel() {
    val config = store.config
    val notice = store.notice
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    fun save(base: String, model: String, secret: String, clear: Boolean = false) {
        if (busy.value) return
        busy.value = true; message.value = null
        viewModelScope.launch {
            try {
                val current = store.config.value
                store.save(if (clear) AiConfig(current.baseUrl, "", current.model) else AiConfig(base.trim(), secret.trim().ifBlank { current.apiKey }, model.trim()))
                message.value = "已保存"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (invalid: IllegalArgumentException) { message.value = invalid.message }
            catch (_: Exception) { message.value = "加密保存失败，请重试" }
            finally { busy.value = false }
        }
    }
}
@Composable
fun AiSettingsPanel(vm: AiSettingsViewModel = hiltViewModel()) {
    val config by vm.config.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var base by remember(config.baseUrl) { mutableStateOf(config.baseUrl) }
    var model by remember(config.model) { mutableStateOf(config.model) }
    // Deliberately not rememberSaveable: plaintext credentials must not enter saved instance state.
    var secret by remember { mutableStateOf("") }
    LaunchedEffect(message) { if (message == "已保存") secret = "" }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("AI 教练 · ${if (config.isMock) "离线 Mock" else "Kimi"}", style = MaterialTheme.typography.titleMedium)
        Text("无密钥时离线工作。配置 Kimi 后，点击发送会将当前任务、最近10条事件类型和8条聊天消息发送至 Kimi，可能产生 API 费用。密钥仅加密保存在本机；清除密钥恢复 Mock。")
        OutlinedTextField(base, { base = it }, label = { Text("AI_BASE_URL · Kimi 官方地址") }, enabled = !busy, singleLine = true)
        OutlinedTextField(model, { model = it }, label = { Text("AI_MODEL") }, enabled = !busy, singleLine = true)
        OutlinedTextField(secret, { secret = it }, label = { Text(if (config.isMock) "AI_API_KEY" else "替换密钥（留空保留）") }, visualTransformation = PasswordVisualTransformation(), enabled = !busy, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.save(base, model, secret) }, enabled = !busy) { Text("保存 AI 设置") }
            OutlinedButton(onClick = { secret = ""; vm.save(base, model, "", clear = true) }, enabled = !busy) { Text("清除密钥") }
        }
        message?.let { Text(it) }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
