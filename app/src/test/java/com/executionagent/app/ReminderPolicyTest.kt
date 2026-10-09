package com.executionagent.app

import com.executionagent.app.domain.model.Task
import com.executionagent.app.domain.model.TaskDraft
import com.executionagent.app.domain.model.TaskStatus
import com.executionagent.app.domain.reminder.ReminderPolicy
import com.executionagent.app.domain.reminder.ReminderRefresh
import com.executionagent.app.domain.repository.AgentSettings
import com.executionagent.app.domain.usecase.TaskOperations
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ReminderPolicyTest {
    private val policy = ReminderPolicy()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val config = AgentSettings(quietEnabled = false)
    private fun at(value: String) = Instant.parse(value).toEpochMilli()
    private val now = at("2026-10-06T02:00:00Z")
    private fun task() = Task("task", "电路", "", "", now - 60_000, now, 30, now, now, nextCheckAt = now)

    @Test fun nextCheckCanRemindBeforeHardDeadline() {
        assertEquals(now, policy.eligibleAt(task(), config, now, zone, 0))
        val future = task().copy(deadlineAt = now + 3_600_000, nextCheckAt = now + 60_000)
        assertEquals(future.nextCheckAt, policy.eligibleAt(future, config, now, zone, 0))
    }
    @Test fun endedAndDisabledTasksHaveNoReminder() {
        for (status in listOf(TaskStatus.COMPLETED, TaskStatus.CANCELLED))
            assertNull(policy.eligibleAt(task().copy(status = status), config, now, zone, 0))
        assertNull(policy.eligibleAt(task(), config.copy(remindersEnabled = false), now, zone, 0))
    }
    @Test fun snoozeAndActiveGraceWaitUntilStoredReminderTime() {
        val next = now + 45 * 60_000
        for (status in listOf(TaskStatus.SNOOZED, TaskStatus.ACTIVE))
            assertEquals(next, policy.notificationAt(task().copy(status = status, nextCheckAt = next), config, now, zone, 0))
        assertEquals(now, policy.eligibleAt(task().copy(status = TaskStatus.ACTIVE, nextCheckAt = next), config, now, zone, 0))
    }
    @Test fun overnightQuietMovesToNextMorningAndEndIsExclusive() {
        val quiet = AgentSettings()
        val night = at("2026-10-06T14:00:00Z") // 22:00
        val morning = at("2026-10-07T00:00:00Z") // 08:00
        assertEquals(morning, policy.outsideQuiet(night, quiet, zone))
        assertEquals(morning, policy.outsideQuiet(morning - 1, quiet, zone))
        assertEquals(morning, policy.outsideQuiet(morning, quiet, zone))
    }
    @Test fun daytimeQuietAndDisabledQuietHaveCorrectBoundaries() {
        val quiet = config.copy(quietEnabled = true, quietStartMinute = 600, quietEndMinute = 720)
        val end = now + 2 * 3_600_000
        assertEquals(end, policy.outsideQuiet(now, quiet, zone))
        assertEquals(end, policy.outsideQuiet(end, quiet, zone))
        assertEquals(now, policy.outsideQuiet(now, config, zone))
    }
    @Test fun dailyQuotaDefersToNextLocalDayThenRespectsQuiet() {
        assertEquals(at("2026-10-07T00:00:00Z"), policy.notificationAt(task(), AgentSettings(dailyLimit = 2), now, zone, 2))
        assertEquals(now, policy.eligibleAt(task(), AgentSettings(dailyLimit = 2), now, zone, 2))
        assertEquals(now, policy.eligibleAt(task(), config.copy(dailyLimit = 2), now, zone, 1))
    }
    @Test fun quietWindowSurvivesDstTransition() {
        val ny = ZoneId.of("America/New_York")
        val night = at("2026-10-31T23:30:00Z") // 19:30 before fall-back
        val quiet = AgentSettings(quietStartMinute = 18 * 60, quietEndMinute = 8 * 60)
        assertEquals(at("2026-11-01T13:00:00Z"), policy.outsideQuiet(night, quiet, ny))
    }
    @Test fun repeatedDstHourCannotMoveQuietEndIntoThePast() {
        val ny = ZoneId.of("America/New_York")
        val quiet = AgentSettings(quietStartMinute = 60, quietEndMinute = 90)
        assertEquals(at("2026-11-01T05:30:00Z"), policy.outsideQuiet(at("2026-11-01T05:15:00Z"), quiet, ny))
        assertEquals(at("2026-11-01T06:30:00Z"), policy.outsideQuiet(at("2026-11-01T06:15:00Z"), quiet, ny))
    }
    @Test fun repeatedSnoozesReduceReminderFrequency() {
        assertEquals(20L, policy.repeatMinutes(task(), now))
        assertEquals(60L, policy.repeatMinutes(task().copy(snoozeCount = 3), now))
        assertTrue(policy.message(task().copy(snoozeCount = 3), now).contains("小步骤"))
    }
    @Test fun settingsRejectInvalidQuietAndQuotaValues() {
        for (invalid in listOf(config.copy(dailyLimit = 0), config.copy(dailyLimit = 21),
            config.copy(quietStartMinute = -1), config.copy(quietEndMinute = 1440), config.copy(quietEndMinute = config.quietStartMinute))) {
            try { invalid.validated(); fail("Invalid settings accepted") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun snoozeCountsPersistAndStaleNotificationCannotActTwice() = runTest {
        val clock = FakeClock(Instant.ofEpochMilli(now))
        val repository = MemoryTaskRepository()
        var refreshes = 0
        val operations = TaskOperations(repository, clock, object : ReminderRefresh { override suspend fun refresh() { refreshes++ } })
        operations.save(null, TaskDraft("电路", "第一题", "", now - 60_000, now, 30))
        val created = repository.observeTasks().first().single()
        operations.snooze(created.id, 20, created.nextCheckAt)
        val snoozed = repository.observeTasks().first().single()
        assertEquals(TaskStatus.SNOOZED, snoozed.status)
        assertEquals(now + 20 * 60_000, snoozed.nextCheckAt)
        assertEquals(1, snoozed.snoozeCount)
        assertEquals(created.deadlineAt, snoozed.deadlineAt)
        try { operations.snooze(created.id, 20, created.nextCheckAt); fail("Stale action accepted") }
        catch (_: IllegalArgumentException) { }
        clock.advance(Duration.ofMinutes(1))
        operations.snooze(created.id, 60)
        assertEquals(2, repository.observeTasks().first().single().snoozeCount)
        operations.start(created.id)
        val active = repository.observeTasks().first().single()
        assertEquals(clock.now().toEpochMilli(), active.nextCheckAt)
        operations.complete(created.id)
        assertNull(repository.observeTasks().first().single().nextCheckAt)
        assertEquals(5, refreshes)
    }
    @Test fun snoozeRejectsInvalidDurationAndTerminalTasks() = runTest {
        val repository = MemoryTaskRepository()
        repository.create(task())
        val operations = TaskOperations(repository, FakeClock(Instant.ofEpochMilli(now)))
        for (minutes in listOf(0, 1441)) {
            try { operations.snooze("task", minutes); fail("Invalid duration accepted") } catch (_: IllegalArgumentException) { }
        }
        operations.cancel("task")
        try { operations.snooze("task", 20); fail("Cancelled task snoozed") } catch (_: IllegalArgumentException) { }
    }
}
