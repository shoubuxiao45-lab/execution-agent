package com.executionagent.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.executionagent.app.data.local.ExecutionDatabase
import com.executionagent.app.data.repository.RoomTaskRepository
import com.executionagent.app.domain.model.Task
import com.executionagent.app.domain.model.TaskStatus
import com.executionagent.app.domain.repository.AgentSettings
import com.executionagent.app.domain.time.ClockProvider
import com.executionagent.app.domain.usecase.TaskOperations
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SnoozeRecoveryTest {
    private val now = Instant.parse("2026-10-06T02:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val config = AgentSettings(quietEnabled = false)
    private val clock = object : ClockProvider { override fun now() = Instant.ofEpochMilli(now); override fun zoneId() = zone }
    private fun task() = Task("task", "电路", "两题", "", now - 60_000, now, 30, now, now, nextCheckAt = now)
    @Test fun notificationSnoozeTokenCanBeConsumedOnlyOnceInRealDatabase() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ExecutionDatabase::class.java).build()
        try {
            val notifications = RecordingNotifications()
            val repository = RoomTaskRepository(database, notifications)
            repository.create(task())
            repository.processReminders(config, now, zone)
            val token = repository.observeTasks().first().single().nextCheckAt
            val operations = TaskOperations(repository, clock)
            operations.snooze("task", 20, token)
            val saved = repository.observeTasks().first().single()
            assertEquals(TaskStatus.SNOOZED, saved.status)
            assertEquals(1, saved.snoozeCount)
            assertEquals(now, saved.deadlineAt)
            assertTrue(notifications.cancelled.contains("task"))
            try { operations.snooze("task", 20, token); fail("Duplicate notification action accepted") } catch (_: IllegalArgumentException) { }
            assertEquals(saved, repository.observeTasks().first().single())
            assertEquals(1, repository.remindersToday(now, zone))
        } finally { database.close() }
    }
    @Test fun reopenedDatabasePreservesReminderQuotaSnoozeAndDoesNotReplayConsumedSlot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "reminder-recovery-${UUID.randomUUID()}.db"
        var database = Room.databaseBuilder(context, ExecutionDatabase::class.java, name).build()
        try {
            val notifications = RecordingNotifications()
            var repository = RoomTaskRepository(database, notifications)
            repository.create(task())
            repository.processReminders(config, now, zone)
            TaskOperations(repository, clock).snooze("task", 20)
            val saved = repository.observeTasks().first().single()
            database.close()
            database = Room.databaseBuilder(context, ExecutionDatabase::class.java, name).build()
            repository = RoomTaskRepository(database, notifications)
            assertEquals(saved, repository.observeTasks().first().single())
            assertEquals(1, repository.remindersToday(now, zone))
            repository.processReminders(config, now, zone)
            assertEquals(1, notifications.posted.size)
            repository.processReminders(config, now + 20 * 60_000 + 1, zone)
            assertEquals(2, notifications.posted.size)
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
