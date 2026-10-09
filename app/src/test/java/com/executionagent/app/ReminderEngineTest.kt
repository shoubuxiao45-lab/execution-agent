package com.executionagent.app

import com.executionagent.app.domain.model.Task
import com.executionagent.app.domain.repository.*
import com.executionagent.app.domain.reminder.*
import com.executionagent.app.domain.usecase.TaskOperations
import com.executionagent.app.reminder.ReminderEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ReminderEngineTest {
    private val now = 1000000L
    private val clock = FakeClock(Instant.ofEpochMilli(now))
    private val config = object : SettingsRepository {
        override val settings = MutableStateFlow(AgentSettings(quietEnabled = false))
        override suspend fun setDarkTheme(enabled: Boolean) { settings.value = settings.value.copy(darkTheme = enabled) }
        override suspend fun setReminderSettings(settings: AgentSettings) { this.settings.value = settings }
    }
    private class Scheduler : ReminderScheduler {
        val scheduled = mutableListOf<Long?>(); var retries = 0; var failRetry = false
        override fun schedule(at: Long?) { scheduled += at }
        override fun retryLater() { retries++; if (failRetry) error("work unavailable") }
    }
    private class Runtime : ReminderRuntime {
        override val state = MutableStateFlow(ReminderRuntimeState())
        override fun scheduled(at: Long?, mode: AlarmMode) { state.value = state.value.copy(nextAlarmAt = at, alarmMode = mode) }
        override fun scanned(at: Long) { state.value = state.value.copy(lastScanAt = at, lastError = null) }
        override fun failed() { state.value = state.value.copy(lastError = "failure") }
    }
    private suspend fun tasks() = MemoryTaskRepository().also { it.create(Task("t", "题目", "", "", now - 60_000, now + 60_000, 10, now - 60_000, now, nextCheckAt = now)) }
    @Test fun refreshSchedulesWithoutClaimingDeliveryAndScanRecordsSuccess() = runTest {
        val scheduler = Scheduler(); val runtime = Runtime(); var scans = 0; val memory = tasks()
        val repo = object : TaskRepository by memory { override suspend fun processReminders(settings: AgentSettings, now: Long, zone: java.time.ZoneId) { scans++ } }
        val engine = ReminderEngine(repo, config, clock, ReminderPolicy(), scheduler, runtime)
        engine.refresh(); assertEquals(0, scans); assertNull(runtime.state.value.lastScanAt); assertEquals(now + 1_000, scheduler.scheduled.last())
        engine.scan(); assertEquals(1, scans); assertEquals(now, runtime.state.value.lastScanAt)
    }
    @Test fun disabledRemindersCancelAndRemoveAlarm() = runTest {
        val scheduler = Scheduler(); var disabled = false; val memory = tasks()
        config.settings.value = config.settings.value.copy(remindersEnabled = false)
        val repo = object : TaskRepository by memory { override suspend fun processReminders(settings: AgentSettings, now: Long, zone: java.time.ZoneId) { disabled = !settings.remindersEnabled } }
        ReminderEngine(repo, config, clock, ReminderPolicy(), scheduler).refresh()
        assertTrue(disabled); assertNull(scheduler.scheduled.last())
    }
    @Test fun concurrentScansAreSerialized() = runTest {
        var active = 0; var peak = 0; val memory = tasks(); val scheduler = Scheduler()
        val repo = object : TaskRepository by memory { override suspend fun processReminders(settings: AgentSettings, now: Long, zone: java.time.ZoneId) { active++; peak = maxOf(peak, active); delay(10); active-- } }
        val engine = ReminderEngine(repo, config, clock, ReminderPolicy(), scheduler)
        (1..20).map { async { engine.scan() } }.awaitAll()
        assertEquals(1, peak); assertEquals(20, scheduler.scheduled.size)
    }
    @Test fun recoveryFailureDoesNotTurnSavedTaskIntoFailedSave() = runTest {
        val memory = tasks(); val scheduler = Scheduler().apply { failRetry = true }; val runtime = Runtime()
        val repo = object : TaskRepository by memory { override suspend fun remindersToday(now: Long, zone: java.time.ZoneId): Int = error("database read") }
        val engine = ReminderEngine(repo, config, clock, ReminderPolicy(), scheduler, runtime)
        TaskOperations(memory, clock, engine).progress("t", 50)
        assertEquals(50, memory.observeTasks().value.single().progress); assertEquals(1, scheduler.retries); assertNotNull(runtime.state.value.lastError)
    }
    @Test fun failedScanIsReportedAndSuccessfulRetryClearsError() = runTest {
        val memory = tasks(); val scheduler = Scheduler(); val runtime = Runtime(); var broken = true
        val repo = object : TaskRepository by memory { override suspend fun processReminders(settings: AgentSettings, now: Long, zone: java.time.ZoneId) { if (broken) error("disk") } }
        val engine = ReminderEngine(repo, config, clock, ReminderPolicy(), scheduler, runtime)
        try { engine.scan(); fail() } catch (_: IllegalStateException) { }
        assertNotNull(runtime.state.value.lastError); assertNull(runtime.state.value.lastScanAt)
        broken = false; engine.scan(); assertNull(runtime.state.value.lastError); assertEquals(now, runtime.state.value.lastScanAt)
    }
    @Test fun cancellationDoesNotRetryOrRecordFailure() = runTest {
        val memory = tasks(); val scheduler = Scheduler(); val runtime = Runtime()
        val repo = object : TaskRepository by memory { override suspend fun remindersToday(now: Long, zone: java.time.ZoneId): Int = throw CancellationException() }
        try { ReminderEngine(repo, config, clock, ReminderPolicy(), scheduler, runtime).refresh(); fail() } catch (_: CancellationException) { }
        assertEquals(0, scheduler.retries); assertNull(runtime.state.value.lastError)
    }
}
