package com.executionagent.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.executionagent.app.data.local.ExecutionDatabase
import com.executionagent.app.data.repository.*
import com.executionagent.app.domain.ai.*
import com.executionagent.app.domain.model.*
import com.executionagent.app.domain.reminder.*
import com.executionagent.app.domain.time.ClockProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CoachTransactionTest {
    private val now = 1000000L
    private val clock = object : ClockProvider { override fun now() = Instant.ofEpochMilli(now); override fun zoneId() = ZoneId.of("Asia/Shanghai") }
    private val task = Task("t", "作业", "三题", "学习", 1, 5000000, 30, 1, 1)
    private fun operations(db: ExecutionDatabase) = CoachOperations(RoomCoachRepository(db, NoTaskNotifications), AgentActionValidator(), clock, NoReminderRefresh)
    @Test fun actionEventAndJournalFailureRollBackTogether() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ExecutionDatabase::class.java).build()
        try {
            val tasks = RoomTaskRepository(db); tasks.create(task)
            val ops = operations(db); val message = ops.propose(task, MockAiProvider().reply(CoachRequest(task, "取消", now)), now)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_ai BEFORE INSERT ON task_events WHEN NEW.type = 'AI_INTERVENTION' BEGIN SELECT RAISE(ABORT, 'test'); END")
            try { ops.apply(message.id); fail() } catch (_: android.database.sqlite.SQLiteException) { }
            assertEquals(task, tasks.observeTasks().first().single())
            assertEquals(ProposalState.PENDING, Json.decodeFromString<Proposal>(db.taskDao().conversation(message.id)!!.content).state)
            assertEquals(1, tasks.observeEvents(task.id).first().size)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_ai")
            ops.apply(message.id); operations(db).apply(message.id)
            assertEquals(3, tasks.observeEvents(task.id).first().size)
            ops.undo(message.id); operations(db).undo(message.id)
            assertEquals(TaskStatus.PLANNED, tasks.observeTasks().first().single().status)
            assertEquals(4, tasks.observeEvents(task.id).first().size)
        } finally { db.close() }
    }
    @Test fun concurrentApplyDoesNotDuplicateSplitAndUndoRemovesUntouchedChildren() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ExecutionDatabase::class.java).build()
        try {
            val tasks = RoomTaskRepository(db); tasks.create(task); val ops = operations(db)
            val message = ops.propose(task, MockAiProvider().reply(CoachRequest(task, "拆分", now)), now)
            listOf(async { runCatching { ops.apply(message.id) } }, async { runCatching { operations(db).apply(message.id) } }).awaitAll()
            assertEquals(3, tasks.observeCount().first())
            assertEquals(1, tasks.observeEvents(task.id).first().count { it.type == TaskEventType.TASK_SPLIT })
            ops.undo(message.id)
            assertEquals(1, tasks.observeCount().first()); assertEquals(TaskStatus.PLANNED, tasks.observeTasks().first().single().status)
        } finally { db.close() }
    }
    @Test fun persistsProposalAcrossReopenAndRejectsUndoAfterChildChat() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>(); val name = "coach-${java.util.UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, ExecutionDatabase::class.java, name).build()
        try {
            RoomTaskRepository(db).create(task)
            val message = operations(db).propose(task, MockAiProvider().reply(CoachRequest(task, "拆分", now)), now)
            db.close(); db = Room.databaseBuilder(context, ExecutionDatabase::class.java, name).build()
            operations(db).apply(message.id)
            val child = RoomTaskRepository(db).observeTasks().first().first { it.id != task.id }
            operations(db).user(child.id, "这一步怎么做")
            try { operations(db).undo(message.id); fail() } catch (_: IllegalArgumentException) { }
            assertEquals(3, RoomTaskRepository(db).observeCount().first())
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
