package com.executionagent.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.executionagent.app.data.local.ExecutionDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseSmokeTest {
    @Test fun createsAllFourTablesAndReadsEmptyDatabase() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ExecutionDatabase::class.java).build()
        try {
            assertEquals(0, database.taskDao().observeCount().first())
            database.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
                val tables = buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
                assertTrue(tables.containsAll(listOf("tasks", "task_events", "conversations", "daily_stats")))
            }
        } finally { database.close() }
    }
}
