package com.executionagent.app

import com.executionagent.app.domain.model.*
import com.executionagent.app.domain.profile.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ExecutionProfileTest {
    private val now = Instant.parse("2026-10-06T12:00:00Z").toEpochMilli()
    private val hour = 60 * 60_000L
    private val zone = ZoneId.of("Asia/Shanghai")
    private val calculator = ExecutionProfileCalculator()
    private fun task(id: String = "t", deadline: Long = now - hour, status: TaskStatus = TaskStatus.PLANNED, completion: Long? = null) =
        Task(id, "任务", "", "", now - 10 * hour, deadline, 30, now - 24 * hour, now, status = status, completedAt = completion)
    private fun event(id: String, type: TaskEventType, at: Long, payload: String = "{}", taskId: String = "t") = TaskEvent(id, taskId, type, at, payload)
    private fun profile(tasks: List<Task> = emptyList(), events: List<TaskEvent> = emptyList(), days: Int = 7, zone: ZoneId = this.zone) = calculator.calculate(ProfileSnapshot(tasks, events), days, now, zone)
    @Test fun emptySamplesRemainUnknownRatherThanZeroSuccess() {
        val p = profile(); assertNull(p.completion.fraction); assertNull(p.onTime.fraction); assertNull(p.averageDelayMinutes)
        assertNull(p.bestTime); assertNull(p.bestDuration); assertEquals(7, p.trends.size); assertEquals(0, p.intervention.observed)
    }
    @Test fun openTasksStayInDenominatorAndCancelledTasksAreSeparate() {
        val p = profile(listOf(task("done", status = TaskStatus.COMPLETED, completion = now - 2 * hour), task("open"), task("cancel", status = TaskStatus.CANCELLED)))
        assertEquals(Rate(1, 2), p.completion); assertEquals(1, p.open); assertEquals(1, p.cancelled); assertEquals(3, p.cohort)
    }
    @Test fun onTimeAndDelayUseValidCompletedTimesIncludingZeroDelay() {
        val p = profile(listOf(task("on", status = TaskStatus.COMPLETED, completion = now - 2 * hour), task("late", status = TaskStatus.COMPLETED, completion = now), task("open")))
        assertEquals(Rate(1, 2), p.onTime); assertEquals(30.0, p.averageDelayMinutes!!, 0.001)
    }
    @Test fun missingOrFutureCompletionTimeIsCountedAndExcludedFromTiming() {
        val p = profile(listOf(task("missing", status = TaskStatus.COMPLETED), task("future", status = TaskStatus.COMPLETED, completion = now + hour)))
        assertEquals(Rate(2, 2), p.completion); assertEquals(2, p.missingCompletionTime); assertNull(p.onTime.fraction)
    }
    @Test fun sevenAndThirtyDayBoundariesUseLocalMidnight() {
        val seven = Instant.parse("2026-09-29T16:00:00Z").toEpochMilli()
        val p = profile(listOf(task("before", seven - 1), task("edge", seven), task("future", now + 1)))
        assertEquals(1, p.cohort); assertEquals(2, profile(listOf(task("before", seven - 1), task("edge", seven)), days = 30).cohort)
    }
    @Test fun timezoneChangesReclassifyDeadlineDates() {
        val deadline = Instant.parse("2026-09-30T01:00:00Z").toEpochMilli()
        assertEquals(1, profile(listOf(task(deadline = deadline)), zone = ZoneId.of("UTC")).cohort)
        assertEquals(0, profile(listOf(task(deadline = deadline)), zone = ZoneId.of("America/Los_Angeles")).cohort)
    }
    @Test fun dstWindowUsesCalendarDaysRatherThanFixedHours() {
        val dstNow = Instant.parse("2026-03-10T12:00:00Z").toEpochMilli()
        val edge = Instant.parse("2026-03-04T05:00:00Z").toEpochMilli()
        val p = calculator.calculate(ProfileSnapshot(listOf(task("edge", edge), task("before", edge - 1)), emptyList()), 7, dstNow, ZoneId.of("America/New_York"))
        assertEquals(1, p.cohort); assertEquals("2026-03-04", p.start.toString())
    }
    @Test fun smallGroupsDoNotInventBestTimes() {
        val four = (1..4).map { task(it.toString(), status = TaskStatus.COMPLETED, completion = now - hour).copy(actualStartAt = now - 2 * hour) }
        assertNull(profile(four).bestTime); assertNull(profile(four).bestDuration)
        val five = four + task("five", status = TaskStatus.COMPLETED, completion = now - hour).copy(actualStartAt = now - 2 * hour)
        assertEquals(Rate(5, 5), profile(five).bestTime!!.completion); assertEquals("16–30分钟", profile(five).bestDuration!!.label)
    }
    @Test fun snoozeCategoryAndDailyTotalsMatchTheCohort() {
        val p = profile(listOf(task("a", status = TaskStatus.COMPLETED, completion = now).copy(category = "学习", snoozeCount = 2), task("b").copy(snoozeCount = 3), task("c", status = TaskStatus.CANCELLED).copy(snoozeCount = 10)))
        assertEquals(5, p.snoozeCount); assertEquals(2, p.categories.size)
        assertEquals(p.completion.count, p.trends.sumOf { it.completion.count }); assertEquals(p.completion.total, p.trends.sumOf { it.completion.total })
        assertEquals(1, p.categories.sumOf { it.cancelled })
    }
    @Test fun matureReminderResponseHasExactlyOneObservation() {
        val events = listOf(event("r", TaskEventType.REMINDER_SENT, now - 2 * hour, """{"policy":{"strategy":"REDUCE_SCOPE"}}"""),
            event("s", TaskEventType.STARTED, now - 2 * hour + 1000), event("c", TaskEventType.COMPLETED, now - hour))
        val p = profile(events = events).intervention
        assertEquals(1, p.observed); assertEquals(1, p.responded); assertEquals(1, p.completed); assertEquals(Rate(1, 1), p.reminders)
        assertEquals("提醒 · 缩小目标", p.strategies.single().strategy)
    }
    @Test fun immatureWindowIsNotFailedIntervention() {
        val p = profile(events = listOf(event("r", TaskEventType.REMINDER_SENT, now - hour + 1))).intervention
        assertEquals(1, p.pending); assertEquals(0, p.observed)
    }
    @Test fun overlapCensorsEarlierInterventionAndDoesNotDoubleCount() {
        val events = listOf(event("r1", TaskEventType.REMINDER_SENT, now - 3 * hour), event("r2", TaskEventType.REMINDER_SENT, now - 3 * hour + 1000),
            event("c", TaskEventType.COMPLETED, now - 2 * hour))
        val p = profile(events = events).intervention
        assertEquals(1, p.overlapping); assertEquals(1, p.observed); assertEquals(1, p.responded)
    }
    @Test fun undoneCoachAndImmediateAiCompletionAreExcluded() {
        val apply = """{"proposalId":"p","operation":"APPLY","strategy":"START_STEP"}"""
        val self = """{"proposalId":"p","operation":"APPLY"}"""
        val events = listOf(event("a", TaskEventType.AI_INTERVENTION, now - 2 * hour, apply), event("c", TaskEventType.COMPLETED, now - 2 * hour + 1, self))
        assertEquals(0, profile(events = events).intervention.responded)
        val undone = events + event("u", TaskEventType.AI_INTERVENTION, now - hour, """{"proposalId":"p","operation":"UNDO"}""")
        assertEquals(1, profile(events = undone).intervention.undone); assertEquals(0, profile(events = undone).intervention.observed)
    }
    @Test fun unrelatedTasksOutOfWindowAndCorruptPayloadsAreSafe() {
        val events = listOf(event("old", TaskEventType.REMINDER_SENT, now - 40 * 24 * hour), event("r", TaskEventType.REMINDER_SENT, now - 2 * hour, "bad"),
            event("c", TaskEventType.COMPLETED, now - hour, taskId = "other"), event("unknown", TaskEventType.AI_INTERVENTION, now - 2 * hour, "bad"))
        val p = profile(events = events).intervention
        assertEquals(1, p.total); assertEquals(1, p.observed); assertEquals(0, p.responded)
    }
}
