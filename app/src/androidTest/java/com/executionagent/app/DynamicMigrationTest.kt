package com.executionagent.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.executionagent.app.data.local.*
import com.executionagent.app.data.repository.RoomTaskRepository
import com.executionagent.app.domain.model.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DynamicMigrationTest {
    private fun seed(context: Context, name: String, nullableLegacyStart: Boolean = false) {
        context.getDatabasePath(name).parentFile?.mkdirs()
        val schema = Json.parseToJsonElement(InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.executionagent.app.data.local.ExecutionDatabase/1.json").bufferedReader().use { it.readText() }).jsonObject.getValue("database").jsonObject
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            db.setForeignKeyConstraintsEnabled(true)
            schema.getValue("entities").jsonArray.forEach { raw ->
                val e = raw.jsonObject; val table = e.getValue("tableName").jsonPrimitive.content
                var sql = e.getValue("createSql").jsonPrimitive.content.replace("\u0024{TABLE_NAME}", table)
                if (nullableLegacyStart && table == "tasks") sql = sql.replace("`startAt` INTEGER NOT NULL", "`startAt` INTEGER")
                db.execSQL(sql)
                e["indices"]?.jsonArray?.forEach { db.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\u0024{TABLE_NAME}", table)) }
            }
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.execSQL("INSERT INTO tasks VALUES ('planned','旧标题','旧目标','学习',100000,10000000,60,100,200,'PLANNED',25,2,10000000,NULL,NULL)")
            db.execSQL("INSERT INTO tasks VALUES ('active','进行中','','',100000,10000000,60,100,200,'ACTIVE',40,1,900000,200000,NULL)")
            db.execSQL("INSERT INTO tasks VALUES ('snoozed','推迟','','',100000,10000000,60,100,200,'SNOOZED',30,3,999000,200000,NULL)")
            db.execSQL("INSERT INTO tasks VALUES ('done','完成','','',100000,10000000,60,100,200,'COMPLETED',100,0,900000,200000,300000)")
            if (nullableLegacyStart) db.execSQL("INSERT INTO tasks VALUES ('missing','缺少计划','','',NULL,10000000,30,100,200,'PLANNED',0,0,10000000,NULL,NULL)")
            db.execSQL("INSERT INTO task_events VALUES ('e1','planned','CREATED',100,'{\"after\":{\"startAt\":100000,\"nextReminderAt\":10000000}}')")
            db.execSQL("INSERT INTO task_events VALUES ('e2','planned','SNOOZED',100,'{\"legacy\":true}')")
            db.execSQL("INSERT INTO conversations VALUES ('c1','planned','user','保留聊天',100)")
            db.execSQL("INSERT INTO daily_stats VALUES ('2026-10-06','Asia/Shanghai',1,1,4,2,100)")
            db.version = 1
        }
    }
    @Test fun realRoomMigrationPreservesTasksChildrenPayloadsAndQuota() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val name = "dynamic-migration-${UUID.randomUUID()}.db"
        seed(context, name)
        var db = Room.databaseBuilder(context, ExecutionDatabase::class.java, name).addMigrations(TaskMigrations.FROM_1_TO_2).build()
        try {
            val rows = RoomTaskRepository(db).observeTasks().first().associateBy { it.id }
            assertEquals(4, rows.size)
            val planned = rows.getValue("planned"); assertEquals(100000L, planned.plannedStartAt); assertEquals(100000L, planned.nextCheckAt)
            assertEquals("旧目标", planned.goal); assertEquals(25, planned.progress); assertEquals(2, planned.snoozeCount)
            assertEquals(3800000L, rows.getValue("active").nextCheckAt)
            assertEquals(999000L, rows.getValue("snoozed").snoozedUntil); assertEquals(TaskStatus.ACTIVE, rows.getValue("snoozed").snoozedFromStatus)
            assertNull(rows.getValue("done").nextCheckAt); assertEquals(300000L, rows.getValue("done").completedAt)
            assertEquals(2, db.taskDao().observeEvents("planned").first().size)
            assertEquals("{\"legacy\":true}", db.taskDao().observeEvents("planned").first().first().payloadJson)
            assertEquals("保留聊天", db.taskDao().observeConversation("planned").first().single().content)
            assertEquals(4, db.taskDao().dailyStats("2026-10-06")?.reminderCount)
            assertEquals(2, db.openHelper.readableDatabase.version)
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            db.close(); db = Room.databaseBuilder(context, ExecutionDatabase::class.java, name).addMigrations(TaskMigrations.FROM_1_TO_2).build()
            assertEquals(rows, RoomTaskRepository(db).observeTasks().first().associateBy { it.id })
            RoomTaskRepository(db).delete("planned")
            assertTrue(db.taskDao().observeEvents("planned").first().isEmpty()); assertTrue(db.taskDao().observeConversation("planned").first().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun legacyMissingStartRemainsNullAndNoFinishIsInvented() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val name = "dynamic-null-migration-${UUID.randomUUID()}.db"
        seed(context, name, true)
        val db = Room.databaseBuilder(context, ExecutionDatabase::class.java, name).addMigrations(TaskMigrations.FROM_1_TO_2).build()
        try { val task = RoomTaskRepository(db).observeTasks().first().single { it.id == "missing" }; assertNull(task.plannedStartAt); assertNull(task.expectedFinishAt) }
        finally { db.close(); context.deleteDatabase(name) }
    }
}
