package com.executionagent.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.executionagent.app.data.local.ExecutionDatabase
import com.executionagent.app.data.repository.RoomTaskRepository
import com.executionagent.app.domain.model.Task
import com.executionagent.app.domain.reminder.TaskNotifications
import com.executionagent.app.domain.repository.AgentSettings
import com.executionagent.app.domain.usecase.TaskOperations
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RecordingNotifications : TaskNotifications {
    var enabled = true
    val posted = mutableListOf<Task>()
    val cancelled = mutableListOf<String>()
    override fun available() = enabled
    override fun post(task: Task, message: String): Boolean { posted += task; return enabled }
    override fun cancel(id: String) { cancelled += id }
}
class ReminderDeliveryTest {
    private val now = Instant.parse("2026-10-06T02:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val config = AgentSettings(quietEnabled = false, dailyLimit = 2)
    private fun task(id: String = "task") = Task(id, "电路", "", "", now - 60_000, now, 30, now, now, nextCheckAt = now)

    @Test fun duplicateScansQuotaAndCompletionCancellation() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ExecutionDatabase::class.java).build()
        try {
            val notifications = RecordingNotifications()
            val repository = RoomTaskRepository(database, notifications)
            repository.create(task())
            (1..10).map { async { repository.processReminders(config, now, zone) } }.awaitAll()
            assertEquals(1, notifications.posted.size)
            assertEquals(1, repository.remindersToday(now, zone))
            repository.processReminders(config, now + 30 * 60_000, zone)
            repository.processReminders(config, now + 60 * 60_000, zone)
            assertEquals(2, notifications.posted.size)
            assertEquals(2, repository.remindersToday(now, zone))
            TaskOperations(repository, object : com.executionagent.app.domain.time.ClockProvider {
                override fun now() = Instant.ofEpochMilli(now)
                override fun zoneId() = zone
            }).complete("task")
            assertTrue(notifications.cancelled.contains("task"))
            assertNull(repository.observeTasks().first().single().nextCheckAt)
            repository.processReminders(config, now + 2 * 3_600_000, zone)
            assertEquals(2, notifications.posted.size)
        } finally { database.close() }
    }
    @Test fun blockedPermissionDoesNotConsumeQuotaAndQuietDoesNotPost() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ExecutionDatabase::class.java).build()
        try {
            val notifications = RecordingNotifications().apply { enabled = false }
            val repository = RoomTaskRepository(database, notifications)
            repository.create(task())
            repository.processReminders(config, now, zone)
            assertEquals(0, repository.remindersToday(now, zone))
            assertTrue(notifications.posted.isEmpty())
            notifications.enabled = true
            repository.processReminders(config.copy(quietEnabled = true, quietStartMinute = 600, quietEndMinute = 720), now + 15 * 60_000, zone)
            assertTrue(notifications.posted.isEmpty())
            repository.processReminders(config, now + 15 * 60_000, zone)
            assertEquals(1, notifications.posted.size)
            repository.processReminders(config.copy(remindersEnabled = false), now + 60 * 60_000, zone)
            assertTrue(notifications.cancelled.contains("task"))
            assertEquals(1, notifications.posted.size)
        } finally { database.close() }
    }
    @Test fun severalDueTasksShareOneGlobalDailyQuota() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ExecutionDatabase::class.java).build()
        try {
            val notifications = RecordingNotifications()
            val repository = RoomTaskRepository(database, notifications)
            for (index in 1..5) repository.create(task("task-$index"))
            repository.processReminders(config, now, zone)
            assertEquals(2, notifications.posted.size)
            repository.processReminders(config, now + 30 * 60_000, zone)
            assertEquals(2, notifications.posted.size)
        } finally { database.close() }
    }
}
