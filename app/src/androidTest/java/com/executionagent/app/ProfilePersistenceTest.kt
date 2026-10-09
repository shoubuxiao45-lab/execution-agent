package com.executionagent.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.executionagent.app.data.local.ExecutionDatabase
import com.executionagent.app.data.repository.*
import com.executionagent.app.domain.model.Task
import com.executionagent.app.domain.profile.ExecutionProfileCalculator
import com.executionagent.app.domain.repository.AgentSettings
import com.executionagent.app.domain.time.ClockProvider
import com.executionagent.app.domain.usecase.TaskOperations
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ProfilePersistenceTest {
    @Test fun profileUsesPersistedTaskAndEventsWithoutChangingReminderQuota() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>(); val name = "profile-${java.util.UUID.randomUUID()}.db"
        val now = Instant.parse("2026-10-06T02:00:00Z").toEpochMilli()
        val clock = object : ClockProvider { override fun now() = Instant.ofEpochMilli(now); override fun zoneId() = ZoneId.of("Asia/Shanghai") }
        var db = Room.databaseBuilder(context, ExecutionDatabase::class.java, name).build()
        try {
            val tasks = RoomTaskRepository(db, RecordingNotifications())
            tasks.create(Task("t", "练习", "一题", "学习", now - 60_000, now, 15, now - 60_000, now, nextCheckAt = now))
            tasks.processReminders(AgentSettings(quietEnabled = false), now, clock.zoneId())
            TaskOperations(tasks, clock).complete("t")
            db.close(); db = Room.databaseBuilder(context, ExecutionDatabase::class.java, name).build()
            val snapshot = RoomProfileRepository(db).observe().first()
            val profile = ExecutionProfileCalculator().calculate(snapshot, 7, now, clock.zoneId())
            assertEquals(1, profile.completion.count); assertEquals(1, profile.onTime.count)
            assertEquals(4, snapshot.events.size)
            assertEquals(1, snapshot.events.count { it.type == com.executionagent.app.domain.model.TaskEventType.OVERDUE_ENTERED })
            assertEquals(1, RoomTaskRepository(db).remindersToday(now, clock.zoneId()))
            RoomTaskRepository(db).delete("t")
            assertTrue(RoomProfileRepository(db).observe().first().events.isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
