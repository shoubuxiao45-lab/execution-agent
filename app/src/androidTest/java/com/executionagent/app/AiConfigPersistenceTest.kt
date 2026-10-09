package com.executionagent.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.executionagent.app.data.ai.SecureAiConfigStore
import com.executionagent.app.domain.ai.AiConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AiConfigPersistenceTest {
    @Test fun encryptedKeySurvivesRecreationAndClearingRestoresMock() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val name = "test-ai-${java.util.UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(ignored: String?, mode: Int) = base.getSharedPreferences(name, mode)
        }
        try {
            val store = SecureAiConfigStore(context)
            assertTrue(store.config.value.isMock)
            store.save(AiConfig(apiKey = "device-test-placeholder"))
            val prefs = base.getSharedPreferences(name, Context.MODE_PRIVATE)
            assertFalse(prefs.all.toString().contains("device-test-placeholder"))
            val recreated = SecureAiConfigStore(context)
            assertEquals("device-test-placeholder", recreated.config.value.apiKey)
            recreated.save(AiConfig())
            assertTrue(SecureAiConfigStore(context).config.value.isMock)
            assertFalse(prefs.contains("AI_API_KEY"))
        } finally { base.deleteSharedPreferences(name) }
    }
}
