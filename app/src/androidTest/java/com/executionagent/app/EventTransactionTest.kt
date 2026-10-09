package com.executionagent.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.executionagent.app.data.local.ExecutionDatabase
import com.executionagent.app.data.repository.RoomTaskRepository
import com.executionagent.app.domain.model.Task
import com.executionagent.app.domain.model.TaskEventType
import com.executionagent.app.domain.repository.AgentSettings
import com.executionagent.app.domain.time.ClockProvider
import com.executionagent.app.domain.usecase.TaskOperations
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EventTransactionTest {
    private val now = Instant.parse("2026-10-06T02:00:00Z").toEpochMilli()
    private val clock = object : ClockProvider { override fun now() = Instant.ofEpochMilli(now); override fun zoneId() = ZoneId.of("Asia/Shanghai") }
    private fun task() = Task("task", "电路", "", "", now - 60_000, now, 30, now, now, nextCheckAt = now)
    @Test fun eventInsertFailureRollsBackTaskWriteAndSuccessfulReminderHasOneEvent() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ExecutionDatabase::class.java).build()
        try {
            val repository = RoomTaskRepository(database, RecordingNotifications())
            repository.create(task())
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_event BEFORE INSERT ON task_events BEGIN SELECT RAISE(ABORT, 'test rejection'); END")
            try { TaskOperations(repository, clock).progress("task", 50); fail("Expected failed transaction") }
            catch (_: android.database.sqlite.SQLiteException) { }
            assertEquals(0, repository.observeTasks().first().single().progress)
            assertEquals(1, repository.observeEvents("task").first().size)
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_event")
            val config = AgentSettings(quietEnabled = false)
            repository.processReminders(config, now, clock.zoneId())
            repository.processReminders(config, now, clock.zoneId())
            val events = repository.observeEvents("task").first()
            assertEquals(listOf(TaskEventType.REMINDER_SENT, TaskEventType.OVERDUE_ENTERED, TaskEventType.CREATED), events.map { it.type })
            assertEquals(1, repository.remindersToday(now, clock.zoneId()))
        } finally { database.close() }
    }
    @Test fun creationAndEventAreAtomicAndOldRowsCanHaveNoHistory() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ExecutionDatabase::class.java).build()
        try {
            val repository = RoomTaskRepository(database)
            database.taskDao().insert(com.executionagent.app.data.local.TaskEntity("legacy", "旧任务", "", "", now, now + 1, 30, now, now))
            assertTrue(repository.observeEvents("legacy").first().isEmpty())
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_event BEFORE INSERT ON task_events BEGIN SELECT RAISE(ABORT, 'test rejection'); END")
            try { repository.create(task()); fail("Expected failed create") }
            catch (_: android.database.sqlite.SQLiteException) { }
            assertNull(database.taskDao().find("task"))
            assertEquals(1, repository.observeCount().first())
        } finally { database.close() }
    }
}
