package com.executionagent.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.executionagent.app.data.local.ExecutionDatabase
import com.executionagent.app.data.repository.RoomTaskRepository
import com.executionagent.app.domain.model.Task
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskPersistenceTest {
    @Test fun persistsAcrossReopenAndDeleteCascadesRelatedHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "phase1-test-${UUID.randomUUID()}.db"
        var database = Room.databaseBuilder(context, ExecutionDatabase::class.java, name).build()
        val task = Task("task", "电路作业", "完成两题", "学习", 1, 2, 30, 1, 1)
        try {
            RoomTaskRepository(database).create(task)
            database.close()
            database = Room.databaseBuilder(context, ExecutionDatabase::class.java, name).build()
            val repository = RoomTaskRepository(database)
            assertEquals(task, repository.observeTasks().first().single())
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO task_events (id,taskId,type,occurredAt,payloadJson) VALUES (?,?,?,?,?)",
                arrayOf<Any>("event", task.id, "CREATED", 1L, "{}"))
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO conversations (id,taskId,role,content,createdAt) VALUES (?,?,?,?,?)",
                arrayOf<Any>("chat", task.id, "USER", "测试", 1L))
            repository.delete(task.id)
            assertEquals(0, repository.observeCount().first())
            for (table in listOf("task_events", "conversations")) {
                database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
                    it.moveToFirst()
                    assertEquals(0, it.getInt(0))
                }
            }
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun atomicUpdatesDoNotLoseConcurrentProgressAndInvalidTransformsRollBack() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ExecutionDatabase::class.java).build()
        try {
            val repository = RoomTaskRepository(database)
            val task = Task("task", "测试", "", "", 1, 2, 30, 1, 1)
            repository.create(task)
            (1..20).map { async { repository.update(task.id) { it.copy(progress = it.progress + 1) } } }.awaitAll()
            assertEquals(20, repository.observeTasks().first().single().progress)
            try {
                repository.update(task.id) { it.copy(id = "changed") }
                throw AssertionError("Invalid identity update accepted")
            } catch (_: IllegalArgumentException) { /* Expected. */ }
            assertEquals(20, repository.observeTasks().first().single().progress)
            assertNull(database.taskDao().find("changed"))
        } finally { database.close() }
    }
}
