package com.executionagent.app

import com.executionagent.app.domain.engine.ExecutionEngine
import com.executionagent.app.domain.engine.InterventionStrategy
import com.executionagent.app.domain.engine.RiskLevel
import com.executionagent.app.domain.model.Task
import com.executionagent.app.domain.model.TaskStatus
import java.time.Duration
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ExecutionEngineTest {
    private val clock = FakeClock(Instant.parse("2026-10-06T02:00:00Z"))
    private val engine = ExecutionEngine(clock)
    private val now = clock.now().toEpochMilli()
    private fun task() = Task("task", "电路", "两题", "学习", now, now + 120 * 60_000, 30, now, now)

    @Test fun riskHasClearLowMediumAndHighBoundaries() {
        assertEquals(RiskLevel.LOW, engine.assess(task().copy(plannedStartAt = now + 60_000)).level)
        val medium = engine.assess(task().copy(deadlineAt = now + 30 * 60_000))
        assertEquals(30, medium.score)
        assertEquals(RiskLevel.MEDIUM, medium.level)
        val high = engine.assess(task().copy(deadlineAt = now))
        assertEquals(60, high.score)
        assertEquals(RiskLevel.HIGH, high.level)
    }
    @Test fun endedTasksAlwaysHaveZeroRiskAndNoIntervention() {
        for (status in listOf(TaskStatus.COMPLETED, TaskStatus.CANCELLED)) {
            val result = engine.assess(task().copy(status = status, deadlineAt = now - 86_400_000, snoozeCount = 100))
            assertEquals(0, result.score)
            assertEquals(InterventionStrategy.NONE, result.strategy)
            assertTrue(result.reasons.isEmpty())
        }
    }
    @Test fun fakeClockDrivesDeadlineHourAndThreeHourEscalation() {
        val task = task().copy(status = TaskStatus.ACTIVE, actualStartAt = now, progress = 100, deadlineAt = now)
        assertEquals(30, engine.assess(task).score)
        clock.advance(Duration.ofMinutes(59))
        assertEquals(30, engine.assess(task).score)
        clock.advance(Duration.ofMinutes(1))
        assertEquals(45, engine.assess(task).score)
        clock.advance(Duration.ofMinutes(120))
        assertEquals(60, engine.assess(task).score)
        assertEquals(InterventionStrategy.KEEP_GOING, engine.assess(task).strategy)
    }
    @Test fun repeatedSnoozesRaiseRiskAndChangeToScopeReductionInsteadOfFasterNagging() {
        val once = engine.assess(task().copy(snoozeCount = 1))
        val twice = engine.assess(task().copy(snoozeCount = 2))
        val third = engine.assess(task().copy(snoozeCount = 3))
        assertEquals(RiskLevel.LOW, once.level)
        assertEquals(RiskLevel.MEDIUM, twice.level)
        assertTrue(third.score > twice.score)
        assertEquals(InterventionStrategy.REDUCE_SCOPE, third.strategy)
        assertEquals(60L, third.repeatMinutes)
        assertEquals(third.score, engine.assess(task().copy(snoozeCount = 50)).score)
    }
    @Test fun progressReducesRemainingTimePressure() {
        val initial = task().copy(deadlineAt = now + 10 * 60_000)
        val improved = initial.copy(progress = 95, status = TaskStatus.ACTIVE, actualStartAt = now)
        assertTrue(engine.assess(improved).score < engine.assess(initial).score)
        assertEquals(InterventionStrategy.KEEP_GOING, engine.assess(improved).strategy)
    }
    @Test fun longHighRiskTaskSuggestsSplittingAndLongDelaySuggestsRescheduling() {
        val long = task().copy(deadlineAt = now, estimatedDurationMinutes = 120)
        assertEquals(InterventionStrategy.SPLIT_TASK, engine.assess(long).strategy)
        clock.advance(Duration.ofHours(3))
        assertEquals(InterventionStrategy.RESCHEDULE, engine.assess(long).strategy)
        assertEquals(60L, engine.assess(long).repeatMinutes)
    }
    @Test fun rulesClampScoresAndDoNotMutateTasks() {
        val original = task().copy(deadlineAt = now - 86_400_000, snoozeCount = Int.MAX_VALUE, progress = -1, estimatedDurationMinutes = Int.MAX_VALUE)
        val copy = original.copy()
        val result = engine.assess(original)
        assertEquals(100, result.score)
        assertEquals(copy, original)
        assertEquals(60L, result.repeatMinutes)
    }
    @Test fun unconfirmedHundredPercentAsksForExplicitCompletion() {
        val result = engine.assess(task().copy(progress = 100, status = TaskStatus.ACTIVE, actualStartAt = now))
        assertEquals(InterventionStrategy.KEEP_GOING, result.strategy)
        assertTrue(result.nextStep.contains("标记完成"))
        val delayed = engine.assess(task().copy(progress = 100, deadlineAt = now - 86_400_000, snoozeCount = 4))
        assertEquals(InterventionStrategy.KEEP_GOING, delayed.strategy)
        assertTrue(delayed.nextStep.contains("标记完成"))
    }
    @Test fun riskDeterminesRepeatIntervalWithQuietAndQuotaHandledSeparately() {
        assertEquals(60L, engine.assess(task().copy(plannedStartAt = now + 60_000)).repeatMinutes)
        assertEquals(30L, engine.assess(task().copy(deadlineAt = now + 30 * 60_000)).repeatMinutes)
        assertEquals(20L, engine.assess(task().copy(deadlineAt = now)).repeatMinutes)
    }
}
