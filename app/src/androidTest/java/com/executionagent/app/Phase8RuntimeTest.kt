package com.executionagent.app

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.executionagent.app.data.ai.SecureAiConfigStore
import com.executionagent.app.data.settings.DataStoreSettingsRepository
import com.executionagent.app.di.AppModule
import com.executionagent.app.domain.ai.AiConfig
import com.executionagent.app.domain.model.TaskDraft
import com.executionagent.app.domain.model.TaskEventType
import com.executionagent.app.domain.repository.AgentSettings
import com.executionagent.app.domain.reminder.AlarmMode
import com.executionagent.app.reminder.AndroidReminderRuntime
import com.executionagent.app.reminder.AndroidReminderScheduler
import com.executionagent.app.reminder.ReminderDependencies
import dagger.hilt.android.EntryPointAccessors
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class Phase8RuntimeTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun isolated() { assumeTrue("Use the validation application", context.packageName.endsWith(".validation")) }
    private fun preferences(name: String) = object : ContextWrapper(context) {
        override fun getSharedPreferences(ignored: String?, mode: Int) = context.getSharedPreferences(name, mode)
    }

    @Test fun damagedCiphertextFallsBackWithoutDeletingAndCanBeReplaced() = runBlocking {
        val name = "phase8-ai-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            assertTrue(prefs.edit().putString("AI_API_KEY", "broken-ciphertext").commit())
            val store = SecureAiConfigStore(preferences(name))
            assertTrue(store.config.value.isMock)
            assertNotNull(store.notice.value)
            assertEquals("broken-ciphertext", prefs.getString("AI_API_KEY", null))
            store.save(AiConfig(apiKey = "phase8-fixture-key"))
            assertNull(store.notice.value)
            assertEquals("phase8-fixture-key", SecureAiConfigStore(preferences(name)).config.value.apiKey)
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun reminderDiagnosticsSurviveRecreationAndSuccessfulScanClearsFailure() {
        val name = "phase8-runtime-${UUID.randomUUID()}"
        try {
            val runtime = AndroidReminderRuntime(preferences(name))
            runtime.scheduled(123_456, AlarmMode.INEXACT)
            runtime.failed()
            val restored = AndroidReminderRuntime(preferences(name))
            assertEquals(123_456L, restored.state.value.nextAlarmAt)
            assertEquals(AlarmMode.INEXACT, restored.state.value.alarmMode)
            assertNotNull(restored.state.value.lastError)
            restored.scanned(234_567)
            assertNull(AndroidReminderRuntime(preferences(name)).state.value.lastError)
            assertEquals(234_567L, restored.state.value.lastScanAt)
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun productionEnginePostsOnceAndCompletionCancelsRealNotification() = runBlocking {
        isolated()
        val dependencies = EntryPointAccessors.fromApplication(context, ReminderDependencies::class.java)
        val operations = dependencies.operations()
        val settings = DataStoreSettingsRepository(AppModule.settingsStore(context))
        val previous = settings.settings.first()
        val manager = context.getSystemService(NotificationManager::class.java)
        val title = "Phase8-${UUID.randomUUID()}"
        var id: String? = null
        try {
            settings.setReminderSettings(AgentSettings(quietEnabled = false, dailyLimit = 20))
            val now = operations.now()
            operations.save(null, TaskDraft(title, "设备通知验证", "验证", now - 120_000, now - 60_000, 5))
            id = operations.observeTasks().first().single { it.title == title }.id
            dependencies.engine().scan()
            repeat(3) { dependencies.engine().scan() }
            repeat(20) { if (manager.activeNotifications.none { it.tag == id }) delay(100) }
            assertEquals(1, manager.activeNotifications.count { it.tag == id })
            assertEquals(1, operations.observeEvents(id).first().count { it.type == TaskEventType.REMINDER_SENT })
            operations.complete(id)
            repeat(20) { if (manager.activeNotifications.any { it.tag == id }) delay(100) }
            assertTrue(manager.activeNotifications.none { it.tag == id })
            dependencies.engine().scan()
            assertTrue(manager.activeNotifications.none { it.tag == id })
        } finally {
            id?.let { operations.delete(it) }
            settings.setReminderSettings(previous)
            dependencies.engine().refresh()
        }
    }

    @Test fun workManagerRunsRealWorkerAndKeepsOneRecoveryRegistration() = runBlocking {
        isolated()
        val manager = WorkManager.getInstance(context)
        repeat(3) { AndroidReminderScheduler.recover(context) }
        var finished = false
        // Instrumentation restarts may leave a real queued worker in the configured
        // 30-second retry backoff. Observe recovery rather than requiring a 10s launch.
        val timeoutAt = android.os.SystemClock.elapsedRealtime() + 45_000
        while (!finished && android.os.SystemClock.elapsedRealtime() < timeoutAt) {
            val scans = manager.getWorkInfosForUniqueWork("reminder-scan").get()
            if (scans.any { work -> work.state == androidx.work.WorkInfo.State.SUCCEEDED }) finished = true
            if (!finished) delay(100)
        }
        assertTrue("ReminderWorker did not succeed: ${manager.getWorkInfosForUniqueWork("reminder-scan").get().map { it.state }}", finished)
        assertEquals(1, manager.getWorkInfosForUniqueWork("reminder-recovery").get().count { !it.state.isFinished })
        assertNotNull(AndroidReminderRuntime(context).state.value.lastScanAt)
    }
}
