package com.executionagent.app

import android.content.Context
import android.app.AlarmManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.executionagent.app.data.settings.DataStoreSettingsRepository
import com.executionagent.app.di.AppModule
import com.executionagent.app.domain.model.TaskDraft
import com.executionagent.app.domain.model.TaskEventType
import com.executionagent.app.domain.repository.AgentSettings
import com.executionagent.app.reminder.ReminderDependencies
import com.executionagent.app.reminder.AndroidReminderRuntime
import com.executionagent.app.domain.reminder.AlarmMode
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Run these methods individually, around host-driven background/reboot/upgrade operations.
 * This fixture must never be run against the user's application package.
 */
class Phase8RecoveryFixture {
    private val context get() = ApplicationProvider.getApplicationContext<Context>().also {
        check(it.packageName.endsWith(".validation")) { "Recovery fixture requires isolated validation app" }
    }
    private val dependencies get() = EntryPointAccessors.fromApplication(context, ReminderDependencies::class.java)
    private val settings get() = DataStoreSettingsRepository(AppModule.settingsStore(context))

    @Test fun seed() = runBlocking {
        val operations = dependencies.operations()
        operations.observeTasks().first().filter { it.title == TITLE }.forEach { operations.delete(it.id) }
        settings.setReminderSettings(AgentSettings(quietEnabled = false, dailyLimit = 20))
        settings.setDarkTheme(true)
        val now = operations.now()
        val delaySeconds = InstrumentationRegistry.getArguments().getString("deadlineSeconds")?.toLong() ?: 60
        require(delaySeconds in 10..300)
        operations.save(null, TaskDraft(TITLE, "保留任务、事件、设置并恢复开始提醒", "验证", now + delaySeconds * 1_000, now + delaySeconds * 1_000 + 3_600_000, 5))
        assertEquals(1, operations.observeTasks().first().count { it.title == TITLE })
        val exact = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        assertEquals(if (exact) AlarmMode.EXACT else AlarmMode.INEXACT, AndroidReminderRuntime(context).state.value.alarmMode)
        // Instrumentation can immediately terminate the process; flush the diagnostic
        // fixture snapshot before the host begins a cross-process observation.
        assertTrue(context.getSharedPreferences("reminder_runtime", Context.MODE_PRIVATE).edit().commit())
    }

    @Test fun probe() = runBlocking {
        val operations = dependencies.operations()
        val task = operations.observeTasks().first().single { it.title == TITLE }
        assertTrue(settings.settings.first().darkTheme)
        assertFalse(settings.settings.first().quietEnabled)
        val events = operations.observeEvents(task.id).first()
        assertTrue(events.any { it.type == TaskEventType.CREATED })
        assertEquals(1, events.count { it.type == TaskEventType.REMINDER_SENT })
        assertTrue(events.single { it.type == TaskEventType.REMINDER_SENT }.payloadJson.contains("START_REMINDER"))
        // Capture notification delivery on the host before instrumentation restarts the target.
    }

    @Test fun cleanup() = runBlocking {
        val operations = dependencies.operations()
        operations.observeTasks().first().filter { it.title == TITLE }.forEach { operations.delete(it.id) }
        settings.setReminderSettings(AgentSettings())
        settings.setDarkTheme(false)
    }

    companion object { private const val TITLE = "Phase8 跨进程恢复样本" }
}
