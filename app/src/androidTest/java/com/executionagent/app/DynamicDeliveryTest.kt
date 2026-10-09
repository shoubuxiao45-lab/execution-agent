package com.executionagent.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.executionagent.app.data.local.ExecutionDatabase
import com.executionagent.app.data.repository.RoomTaskRepository
import com.executionagent.app.domain.engine.NotificationType
import com.executionagent.app.domain.model.*
import com.executionagent.app.domain.reminder.TaskNotifications
import com.executionagent.app.domain.repository.AgentSettings
import com.executionagent.app.domain.time.ClockProvider
import com.executionagent.app.domain.usecase.TaskOperations
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DynamicDeliveryTest {
    private val start = Instant.parse("2026-10-06T12:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Shanghai")
    private class Notices : TaskNotifications {
        var enabled = true
        val types = mutableListOf<NotificationType>(); val cancelled = mutableListOf<String>()
        override fun available() = enabled
        override fun post(task: Task, message: String) = enabled
        override fun post(task: Task, message: String, type: NotificationType): Boolean { if (enabled) types += type; return enabled }
        override fun cancel(id: String) { cancelled += id }
    }
    @Test fun realRepositoryStartSilentMidpointFinishDeadlineSnoozeAndCompletionTrajectory() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ExecutionDatabase::class.java).build()
        try {
            var now = start; val notices = Notices(); val repo = RoomTaskRepository(db, notices)
            val clock = object : ClockProvider { override fun now() = Instant.ofEpochMilli(now); override fun zoneId() = zone }
            val ops = TaskOperations(repo, clock); val config = AgentSettings(quietEnabled = false, dailyLimit = 20)
            ops.save(null, TaskDraft("电路", "五题", "学习", start, start + 120 * 60_000, 60))
            val id = repo.observeTasks().first().single().id
            repo.processReminders(config, now, zone)
            assertEquals(listOf(NotificationType.START_REMINDER), notices.types)
            ops.start(id, repo.observeTasks().first().single().nextCheckAt)
            val active = repo.observeTasks().first().single(); assertEquals(TaskStatus.ACTIVE, active.status); assertEquals(start, active.actualStartAt)
            now = requireNotNull(active.nextCheckAt); repo.processReminders(config, now, zone)
            assertEquals(1, notices.types.size); assertEquals(start + 60 * 60_000, repo.observeTasks().first().single().nextCheckAt)
            now = start + 60 * 60_000; repo.processReminders(config, now, zone)
            assertEquals(NotificationType.EXPECTED_FINISH_CHECK, notices.types.last()); assertEquals(TaskStatus.ACTIVE, repo.observeTasks().first().single().status)
            now = start + 120 * 60_000; repo.processReminders(config, now, zone)
            assertEquals(NotificationType.OVERDUE_INTERVENTION, notices.types.last()); assertEquals(TaskStatus.OVERDUE, repo.observeTasks().first().single().status)
            ops.snooze(id, 10); val snoozed = repo.observeTasks().first().single(); assertEquals(now + 10 * 60_000, snoozed.nextCheckAt)
            val snooze = repo.observeEvents(id).first().first { it.type == TaskEventType.SNOOZED }
            assertEquals(10, Json.parseToJsonElement(snooze.payloadJson).jsonObject.getValue("minutes").jsonPrimitive.int)
            now += 5 * 60_000; repo.processReminders(config, now, zone); assertEquals(TaskStatus.SNOOZED, repo.observeTasks().first().single().status)
            now += 5 * 60_000; repo.processReminders(config, now, zone); assertEquals(TaskStatus.OVERDUE, repo.observeTasks().first().single().status)
            val events = repo.observeEvents(id).first(); assertEquals(1, events.count { it.type == TaskEventType.OVERDUE_ENTERED }); assertEquals(1, events.count { it.type == TaskEventType.EXPECTED_FINISH_REACHED }); assertEquals(1, events.count { it.type == TaskEventType.PROGRESS_CHECK })
            ops.complete(id); assertNull(repo.observeTasks().first().single().nextCheckAt); assertTrue(notices.cancelled.contains(id))
            val count = notices.types.size; now += 3600_000; repo.processReminders(config, now, zone); assertEquals(count, notices.types.size)
        } finally { db.close() }
    }
    @Test fun quietDeadlineTransitionsOnceButDoesNotNotifyUntilMorning() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ExecutionDatabase::class.java).build()
        try {
            val notices = Notices(); val repo = RoomTaskRepository(db, notices); val deadline = start + 120 * 60_000
            repo.create(Task("t", "安静验证", "", "", start, deadline, 60, start, start, status = TaskStatus.ACTIVE, actualStartAt = start, nextCheckAt = deadline))
            repo.processReminders(AgentSettings(), deadline, zone)
            val current = repo.observeTasks().first().single(); assertEquals(TaskStatus.OVERDUE, current.status)
            assertEquals(Instant.parse("2026-10-07T00:00:00Z").toEpochMilli(), current.nextCheckAt); assertTrue(notices.types.isEmpty())
            assertEquals(0, repo.remindersToday(deadline, zone)); assertEquals(1, repo.observeEvents("t").first().count { it.type == TaskEventType.OVERDUE_ENTERED })
            repo.processReminders(AgentSettings(), requireNotNull(current.nextCheckAt), zone); assertEquals(listOf(NotificationType.OVERDUE_INTERVENTION), notices.types)
        } finally { db.close() }
    }
    @Test fun blockedDeliveryKeepsRetryAndDoesNotClaimStartOrConsumeQuota() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ExecutionDatabase::class.java).build()
        try {
            val notices = Notices().apply { enabled = false }; val repo = RoomTaskRepository(db, notices)
            repo.create(Task("t", "权限验证", "", "", start, null, 20, start, start, nextCheckAt = start))
            repo.processReminders(AgentSettings(quietEnabled = false), start, zone)
            val task = repo.observeTasks().first().single(); assertEquals(start + 15 * 60_000, task.nextCheckAt); assertFalse(task.startReminderSent)
            assertEquals(0, repo.remindersToday(start, zone)); assertTrue(repo.observeEvents("t").first().none { it.type == TaskEventType.REMINDER_SENT })
            notices.enabled = true; repo.processReminders(AgentSettings(quietEnabled = false), requireNotNull(task.nextCheckAt), zone)
            assertEquals(listOf(NotificationType.START_REMINDER), notices.types)
        } finally { db.close() }
    }
    @Test fun snoozeCrossingDeadlinePersistsSilentBoundaryBeforeChosenWake() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ExecutionDatabase::class.java).build()
        try {
            val notices = Notices(); val repo = RoomTaskRepository(db, notices)
            repo.create(Task("t", "推迟边界", "", "", start, start + 5 * 60_000, 20, start, start,
                status = TaskStatus.SNOOZED, nextCheckAt = start + 10 * 60_000, snoozedUntil = start + 10 * 60_000, snoozedFromStatus = TaskStatus.PLANNED))
            val settings = AgentSettings(quietEnabled = false)
            repo.processReminders(settings, start + 5 * 60_000, zone)
            val waiting = repo.observeTasks().first().single(); assertEquals(TaskStatus.OVERDUE, waiting.status)
            assertEquals(start + 10 * 60_000, waiting.nextCheckAt); assertTrue(notices.types.isEmpty()); assertEquals(0, repo.remindersToday(start, zone))
            repo.processReminders(settings, start + 10 * 60_000, zone)
            assertEquals(listOf(NotificationType.OVERDUE_INTERVENTION), notices.types)
            assertEquals(1, repo.observeEvents("t").first().count { it.type == TaskEventType.OVERDUE_ENTERED })
        } finally { db.close() }
    }
}
