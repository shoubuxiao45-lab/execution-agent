package com.executionagent.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.Database
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @androidx.room.Transaction
    @Query("SELECT * FROM tasks ORDER BY id")
    fun observeExecutionRecords(): Flow<List<com.executionagent.app.data.repository.LocalExecutionRecord>>
    @Query("SELECT * FROM conversations WHERE taskId = :id ORDER BY createdAt DESC, rowid DESC LIMIT 100")
    fun observeConversation(id: String): Flow<List<ConversationEntity>>
    @Insert suspend fun insertConversation(message: ConversationEntity)
    @Query("SELECT * FROM conversations WHERE id = :id") suspend fun conversation(id: String): ConversationEntity?
    @Query("UPDATE conversations SET content = :content WHERE id = :id AND content = :expected")
    suspend fun replaceConversation(id: String, expected: String, content: String): Int
    @Query("SELECT COUNT(*) FROM conversations WHERE taskId = :id") suspend fun conversationCount(id: String): Int
    @Query("SELECT COUNT(*) FROM task_events WHERE taskId = :id") suspend fun eventCount(id: String): Int
    @Query("SELECT * FROM task_events ORDER BY occurredAt, rowid") fun observeAllEvents(): Flow<List<TaskEventEntity>>
    @Query("SELECT COUNT(*) FROM tasks")
    fun observeCount(): Flow<Int>
    @Query("SELECT * FROM tasks ORDER BY deadlineAt, createdAt, id")
    fun observeTasks(): Flow<List<TaskEntity>>
    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun find(id: String): TaskEntity?
    @Insert
    suspend fun insert(task: TaskEntity)
    @Update
    suspend fun update(task: TaskEntity): Int
    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: String): Int
    @Query("SELECT * FROM tasks WHERE status NOT IN ('COMPLETED', 'CANCELLED') ORDER BY deadlineAt, id")
    suspend fun openTasks(): List<TaskEntity>
    @Query("SELECT * FROM daily_stats WHERE localDate = :date")
    suspend fun dailyStats(date: String): DailyStatsEntity?
    @Upsert
    suspend fun saveDailyStats(stats: DailyStatsEntity)
    @Insert
    suspend fun insertEvent(event: TaskEventEntity)
    @Query("SELECT * FROM task_events WHERE taskId = :id ORDER BY occurredAt DESC, rowid DESC LIMIT 100")
    fun observeEvents(id: String): Flow<List<TaskEventEntity>>
}

@Database(entities = [TaskEntity::class, TaskEventEntity::class, ConversationEntity::class,
    DailyStatsEntity::class], version = 2, exportSchema = true)
@TypeConverters(DatabaseConverters::class)
abstract class ExecutionDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
}
