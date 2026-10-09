package com.executionagent.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.executionagent.app.domain.model.TaskEventType
import com.executionagent.app.domain.model.TaskStatus

@Entity(tableName = "tasks", indices = [Index("deadlineAt"), Index("status")])
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val goal: String,
    val category: String,
    val plannedStartAt: Long?,
    val deadlineAt: Long?,
    val estimatedDurationMinutes: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val status: TaskStatus = TaskStatus.PLANNED,
    val progress: Int = 0,
    val snoozeCount: Int = 0,
    val nextCheckAt: Long? = null,
    val actualStartAt: Long? = null,
    val completedAt: Long? = null,
    val prepareReminderMinutes: Int? = null,
    @androidx.room.ColumnInfo(defaultValue = "'NORMAL'") val reminderMode: com.executionagent.app.domain.model.ReminderMode = com.executionagent.app.domain.model.ReminderMode.NORMAL,
    @androidx.room.ColumnInfo(defaultValue = "0") val prepareReminderSent: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "0") val startReminderSent: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "0") val expectedFinishReached: Boolean = false,
    val overdueEnteredAt: Long? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val overdueCheckCount: Int = 0,
    val lastCheckAt: Long? = null,
    val snoozedUntil: Long? = null,
    val snoozedFromStatus: TaskStatus? = null
)

@Entity(tableName = "task_events", indices = [Index("taskId"), Index("occurredAt")],
    foreignKeys = [ForeignKey(entity = TaskEntity::class, parentColumns = ["id"],
        childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)])
data class TaskEventEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val type: TaskEventType,
    val occurredAt: Long,
    val payloadJson: String = "{}"
)

@Entity(tableName = "conversations", indices = [Index("taskId")],
    foreignKeys = [ForeignKey(entity = TaskEntity::class, parentColumns = ["id"],
        childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)])
data class ConversationEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val role: String,
    val content: String,
    val createdAt: Long
)

@Entity(tableName = "daily_stats")
data class DailyStatsEntity(
    @PrimaryKey val localDate: String,
    val zoneId: String,
    val completedCount: Int = 0,
    val onTimeCount: Int = 0,
    val reminderCount: Int = 0,
    val snoozeCount: Int = 0,
    val updatedAt: Long
)
