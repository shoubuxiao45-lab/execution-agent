package com.executionagent.app

import com.executionagent.app.domain.engine.*
import com.executionagent.app.domain.model.*
import com.executionagent.app.domain.reminder.*
import com.executionagent.app.domain.repository.AgentSettings
import com.executionagent.app.domain.usecase.TaskOperations
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DynamicReminderTest {
    private val clock = FakeClock(Instant.parse("2026-10-06T12:00:00Z")) // 20:00 Shanghai
    private val engine = ExecutionEngine(clock)
    private val start = clock.now().toEpochMilli()
    private fun task() = Task("t", "电路作业", "完成5题", "学习", start, start + 120 * 60_000, 60, start, start, nextCheckAt = start)

    @Test fun plannedStartEmitsStartReminderAtTwenty() {
        val d = engine.check(task()); assertTrue(d.shouldNotify); assertEquals(NotificationType.START_REMINDER, d.notificationType)
        assertEquals(TaskStatus.PLANNED, d.task.status); assertTrue(requireNotNull(d.nextCheckAt) > start)
    }
    @Test fun firstCheckUsesStartInsteadOfTwentyTwoDeadline() { assertEquals(start, engine.calculateNextCheck(task())); assertNotEquals(task().deadlineAt, engine.calculateNextCheck(task())) }
    @Test fun expectedFinishIsTwentyOneForSixtyMinutes() { assertEquals(start + 60 * 60_000, task().expectedFinishAt) }
    @Test fun expectedFinishDoesNotMarkOverdue() {
        val d = engine.check(engine.begin(task(), start), start + 60 * 60_000)
        assertEquals(TaskStatus.ACTIVE, d.task.status); assertEquals(NotificationType.EXPECTED_FINISH_CHECK, d.notificationType); assertTrue(d.task.isPlanOverrun)
    }
    @Test fun hardDeadlineMarksOverdueAndRecordsEntryTime() {
        val d = engine.check(engine.begin(task(), start), start + 120 * 60_000)
        assertEquals(TaskStatus.OVERDUE, d.statusUpdate); assertEquals(start + 120 * 60_000, d.task.overdueEnteredAt)
        assertEquals(NotificationType.OVERDUE_INTERVENTION, d.notificationType)
    }
    @Test fun activeTwentyMinutesSkipsMidpoint() { val active = engine.begin(task().copy(estimatedDurationMinutes = 20), start); assertEquals(start + 20 * 60_000, active.nextCheckAt) }
    @Test fun activeSixtyMinutesHasSilentMidpoint() {
        val active = engine.begin(task(), start); assertEquals(start + 30 * 60_000, active.nextCheckAt)
        val d = engine.check(active, start + 30 * 60_000); assertFalse(d.shouldNotify); assertEquals(NotificationType.PROGRESS_CHECK, d.notificationType)
        assertEquals(start + 60 * 60_000, d.nextCheckAt)
    }
    @Test fun activeNinetyMinutesUsesFortyAndSeventyFivePercent() {
        val active = engine.begin(task().copy(estimatedDurationMinutes = 90), start)
        assertEquals(start + 36 * 60_000, active.nextCheckAt)
        assertEquals(start + 67 * 60_000 + 30_000, engine.check(active, requireNotNull(active.nextCheckAt)).nextCheckAt)
    }
    @Test fun longTaskHasOneFinishCheckAndSuggestsSplitting() {
        val long = task().copy(estimatedDurationMinutes = 180, deadlineAt = null)
        assertEquals(start + 180 * 60_000, engine.begin(long, start).nextCheckAt)
        assertEquals(InterventionStrategy.SPLIT_TASK, engine.check(long).suggestedStrategy)
    }
    @Test fun snoozeTenPersistsOneNextCheckAndResumesPlanned() {
        val snoozed = engine.snooze(task(), 10, start)
        assertEquals(start + 10 * 60_000, snoozed.nextCheckAt); assertEquals(1, snoozed.snoozeCount)
        val d = engine.check(snoozed, start + 10 * 60_000); assertEquals(TaskStatus.PLANNED, d.task.status); assertNull(d.task.snoozedUntil)
    }
    @Test fun snoozedActiveResumesExecutionInsteadOfUnstartedNag() {
        val snoozed = engine.snooze(engine.begin(task(), start), 10, start)
        val d = engine.check(snoozed, start + 10 * 60_000); assertEquals(TaskStatus.ACTIVE, d.task.status); assertFalse(d.shouldNotify)
    }
    @Test fun snoozeAcrossDeadlineResumesOverdue() { val d = engine.check(engine.snooze(task(), 10, start + 115 * 60_000), start + 125 * 60_000); assertEquals(TaskStatus.OVERDUE, d.task.status) }
    @Test fun completionClearsNextCheckAndRefreshesScheduler() = runTest {
        val repository = MemoryTaskRepository(); repository.create(task()); var refreshes = 0
        val ops = TaskOperations(repository, clock, object : ReminderRefresh { override suspend fun refresh() { refreshes++ } })
        ops.complete("t"); assertNull(repository.observeTasks().first().single().nextCheckAt); assertEquals(1, refreshes)
        assertNull(engine.calculateNextCheck(task().copy(status = TaskStatus.CANCELLED)))
    }
    @Test fun quietHoursMoveOrdinaryButPreserveExplicitReminder() {
        val at = start + 180 * 60_000 // 23:00
        val ordinary = task().copy(deadlineAt = null, plannedStartAt = at, nextCheckAt = at)
        val policy = ReminderPolicy(engine); val expected = Instant.parse("2026-10-07T00:00:00Z").toEpochMilli()
        assertEquals(expected, policy.eligibleAt(ordinary, AgentSettings(), at, clock.zoneId(), 0))
        assertEquals(at, policy.eligibleAt(ordinary.copy(reminderMode = ReminderMode.EXPLICIT), AgentSettings(), at, clock.zoneId(), 0))
    }
    @Test fun repeatedSnoozesChangeInterventionAndDoNotIncreaseFrequency() {
        val overdue = task().copy(deadlineAt = start)
        assertEquals(InterventionStrategy.START_STEP, engine.check(overdue).suggestedStrategy)
        assertEquals(InterventionStrategy.REDUCE_SCOPE, engine.check(overdue.copy(snoozeCount = 2)).suggestedStrategy)
        assertEquals(InterventionStrategy.RESCHEDULE, engine.check(overdue.copy(snoozeCount = 4)).suggestedStrategy)
        assertTrue(engine.adaptiveMinutes(overdue.copy(snoozeCount = 4)) >= engine.adaptiveMinutes(overdue))
    }
    @Test fun fakeClockMovesFromSilentProgressToExpectedFinishToOverdue() {
        var t = engine.begin(task(), start); clock.advance(Duration.ofMinutes(30)); val midpoint = engine.check(t)
        assertFalse(midpoint.shouldNotify); t = midpoint.task; clock.advance(Duration.ofMinutes(30)); val finish = engine.check(t)
        assertEquals(NotificationType.EXPECTED_FINISH_CHECK, finish.notificationType); clock.advance(Duration.ofMinutes(60))
        assertEquals(TaskStatus.OVERDUE, engine.check(finish.task).task.status)
    }
    @Test fun noDeadlineNeverFabricatesHardOverdue() { val d = engine.check(engine.begin(task().copy(deadlineAt = null), start), start + 600 * 60_000); assertEquals(TaskStatus.ACTIVE, d.task.status); assertTrue(d.task.isPlanOverrun); assertNull(d.task.overdueEnteredAt) }
    @Test fun preparationDefaultOffAndConfiguredOnceWithoutEscalation() {
        val future = task().copy(plannedStartAt = start + 30 * 60_000)
        assertEquals(future.plannedStartAt, engine.calculateNextCheck(future))
        val enabled = future.copy(prepareReminderMinutes = 10)
        assertEquals(start + 20 * 60_000, engine.calculateNextCheck(enabled))
        val d = engine.check(enabled, start + 20 * 60_000); assertEquals(NotificationType.PREPARE_REMINDER, d.notificationType)
        assertEquals(TaskStatus.PLANNED, d.task.status); assertEquals(0, d.task.overdueCheckCount); assertEquals(future.plannedStartAt, d.nextCheckAt)
    }
    @Test fun stillWorkingUsesRemainingDurationAndClampsHardBoundary() {
        val active = engine.begin(task(), start).copy(progress = 80)
        assertEquals(start + 60 * 60_000 + 10 * 60_000, engine.begin(active, start + 60 * 60_000, true).nextCheckAt)
        assertEquals(task().deadlineAt, engine.begin(active.copy(progress = 0), start + 115 * 60_000, true).nextCheckAt)
    }
    @Test fun missingLegacyPlanRemainsMissingAndHasNoFakeFinish() { val legacy = task().copy(plannedStartAt = null); assertNull(legacy.expectedFinishAt); assertEquals(legacy.deadlineAt, engine.calculateNextCheck(legacy)) }
    @Test fun preparationCannotTurnDisabledTaskIntoReminder() { assertNull(ReminderPolicy(engine).eligibleAt(task(), AgentSettings(remindersEnabled = false), start, clock.zoneId(), 0)) }
    @Test fun titleOnlyEditPreservesSnoozeAndTimeFlags() = runTest {
        val repo = MemoryTaskRepository(); val snoozed = engine.snooze(task(), 10, start).copy(startReminderSent = true)
        repo.create(snoozed); TaskOperations(repo, clock).save("t", TaskDraft("新标题", snoozed.goal, snoozed.category, snoozed.plannedStartAt, snoozed.deadlineAt, snoozed.estimatedDurationMinutes))
        val edited = repo.observeTasks().first().single(); assertEquals(TaskStatus.SNOOZED, edited.status); assertEquals(snoozed.nextCheckAt, edited.nextCheckAt); assertTrue(edited.startReminderSent)
    }
    @Test fun oldProposalTaskJsonKeepsTimeFieldCompatibility() {
        val json = """{"id":"legacy","title":"旧任务","goal":"","category":"","startAt":1000,"deadlineAt":5000,"estimatedMinutes":30,"createdAt":1,"updatedAt":2,"nextReminderAt":4000,"startedAt":2000}"""
        val decoded = kotlinx.serialization.json.Json.decodeFromString<Task>(json)
        assertEquals(1000L, decoded.plannedStartAt); assertEquals(2000L, decoded.actualStartAt); assertEquals(4000L, decoded.nextCheckAt)
        assertEquals(ReminderMode.NORMAL, decoded.reminderMode); assertNull(decoded.prepareReminderMinutes)
    }
    @Test fun deadlineDuringSnoozeSwitchesStrategySilentlyAndKeepsTenMinuteWake() {
        val snoozed = engine.snooze(task().copy(deadlineAt = start + 5 * 60_000), 10, start)
        val boundary = engine.check(snoozed, start + 5 * 60_000)
        assertFalse(boundary.shouldNotify); assertEquals(TaskStatus.OVERDUE, boundary.task.status)
        assertEquals(start + 10 * 60_000, boundary.nextCheckAt); assertEquals(0, boundary.task.overdueCheckCount)
        val wake = engine.check(boundary.task, start + 10 * 60_000); assertTrue(wake.shouldNotify); assertNull(wake.task.snoozedUntil)
    }
    @Test fun noDeadlineProfileIncludesPlannedFinishButDoesNotInventOnTimeRate() {
        val done = task().copy(deadlineAt = null, status = TaskStatus.COMPLETED, completedAt = start + 65 * 60_000)
        val profile = com.executionagent.app.domain.profile.ExecutionProfileCalculator().calculate(
            com.executionagent.app.domain.profile.ProfileSnapshot(listOf(done), emptyList()), 7, start + 120 * 60_000, clock.zoneId())
        assertEquals(1, profile.cohort); assertEquals(1, profile.completion.count); assertEquals(0, profile.onTime.total); assertNull(profile.averageDelayMinutes)
    }
}
