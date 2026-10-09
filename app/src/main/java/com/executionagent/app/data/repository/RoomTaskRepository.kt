package com.executionagent.app.data.repository

import androidx.room.withTransaction
import com.executionagent.app.data.local.ExecutionDatabase
import com.executionagent.app.data.local.TaskEntity
import com.executionagent.app.data.local.DailyStatsEntity
import com.executionagent.app.data.local.TaskEventEntity
import com.executionagent.app.domain.model.Task
import com.executionagent.app.domain.model.TaskStatus
import com.executionagent.app.domain.model.TaskEvent
import com.executionagent.app.domain.model.TaskEventType
import com.executionagent.app.domain.engine.RiskAssessment
import com.executionagent.app.domain.repository.AgentSettings
import com.executionagent.app.domain.reminder.TaskNotifications
import com.executionagent.app.domain.reminder.NoTaskNotifications
import com.executionagent.app.domain.reminder.ReminderPolicy
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import com.executionagent.app.domain.repository.TaskRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.map

class RoomTaskRepository @Inject constructor(
    private val database: ExecutionDatabase,
    private val notifications: TaskNotifications = NoTaskNotifications,
    private val policy: ReminderPolicy = ReminderPolicy()
) : TaskRepository {
    private val dao = database.taskDao()
    override fun observeCount() = dao.observeCount()
    override fun observeTasks() = dao.observeTasks().map { tasks -> tasks.map { it.toTask() } }
    override fun observeEvents(id: String) = dao.observeEvents(id).map { events -> events.map { TaskEvent(it.id, it.taskId, it.type, it.occurredAt, it.payloadJson) } }
    override suspend fun create(task: Task) {
        database.withTransaction {
            dao.insert(task.toEntity())
            writeEvent(null, task, TaskEventType.CREATED, task.createdAt)
        }
    }
    override suspend fun update(id: String, eventType: TaskEventType?, occurredAt: Long?, transform: (Task) -> Task) {
        database.withTransaction {
            val current = requireNotNull(dao.find(id)) { "任务已不存在" }.toTask()
            val updated = transform(current)
            require(updated.id == id) { "不能修改任务标识" }
            check(dao.update(updated.toEntity()) == 1) { "任务保存失败" }
            if (updated != current) {
                if (eventType != null) {
                    writeEvent(current, updated, eventType, occurredAt ?: updated.updatedAt)
                    if (eventType == TaskEventType.EDITED && (current.plannedStartAt != updated.plannedStartAt || current.deadlineAt != updated.deadlineAt))
                        writeEvent(current, updated, TaskEventType.RESCHEDULED, occurredAt ?: updated.updatedAt)
                }
                notifications.cancel(id)
            }
        }
    }
    override suspend fun delete(id: String) {
        database.withTransaction {
            require(dao.delete(id) == 1) { "任务已不存在" }
            notifications.cancel(id)
        }
    }
    override suspend fun remindersToday(now: Long, zone: ZoneId): Int =
        dao.dailyStats(Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString())?.reminderCount ?: 0

    override suspend fun processReminders(settings: AgentSettings, now: Long, zone: ZoneId) {
        database.withTransaction {
            val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
            var stats = dao.dailyStats(date) ?: DailyStatsEntity(date, zone.id, updatedAt = now)
            val pending = dao.openTasks().sortedWith(compareBy<TaskEntity> { it.nextCheckAt ?: Long.MAX_VALUE }
                .thenByDescending { policy.decision(it.toTask(), now).score }.thenBy { it.id })
            for (entity in pending) {
                val task = entity.toTask()
                if (!settings.remindersEnabled) { notifications.cancel(task.id); continue }
                val eligible = policy.eligibleAt(task, settings, now, zone, stats.reminderCount) ?: continue
                if (eligible > now) continue
                val decision = policy.check(task, now)
                val next = decision.task
                val deliveryAt = policy.notificationAt(task, settings, now, zone, stats.reminderCount) ?: continue
                // Keep notification posting/cancellation serialized with task writes in Room's transaction.
                if (!decision.shouldNotify) {
                    dao.update(next.toEntity())
                    if (task.overdueEnteredAt == null && next.overdueEnteredAt != null)
                        writeEvent(task, next, TaskEventType.OVERDUE_ENTERED, now, "ENGINE")
                    if (decision.notificationType == com.executionagent.app.domain.engine.NotificationType.PROGRESS_CHECK)
                        writeEvent(task, next, TaskEventType.PROGRESS_CHECK, now, "ENGINE")
                } else if (deliveryAt > now) {
                    val deferred = task.copy(status = next.status, overdueEnteredAt = next.overdueEnteredAt,
                        expectedFinishReached = next.expectedFinishReached, nextCheckAt = deliveryAt,
                        snoozedUntil = next.snoozedUntil, snoozedFromStatus = next.snoozedFromStatus)
                    dao.update(deferred.toEntity())
                    if (task.overdueEnteredAt == null && deferred.overdueEnteredAt != null)
                        writeEvent(task, deferred, TaskEventType.OVERDUE_ENTERED, now, "ENGINE")
                    if (!task.expectedFinishReached && deferred.expectedFinishReached)
                        writeEvent(task, deferred, TaskEventType.EXPECTED_FINISH_REACHED, now, "ENGINE")
                } else if (notifications.available() && notifications.post(next, policy.checkMessage(task, decision), requireNotNull(decision.notificationType))) {
                    dao.update(next.toEntity())
                    if (task.overdueEnteredAt == null && next.overdueEnteredAt != null)
                        writeEvent(task, next, TaskEventType.OVERDUE_ENTERED, now, "ENGINE")
                    if (!task.expectedFinishReached && next.expectedFinishReached)
                        writeEvent(task, next, TaskEventType.EXPECTED_FINISH_REACHED, now, "ENGINE")
                    writeEvent(task, next, TaskEventType.REMINDER_SENT, now, "REMINDER", policy.decision(task, now).copy(
                        strategy = decision.suggestedStrategy, nextStep = policy.checkMessage(task, decision),
                        repeatMinutes = ((next.nextCheckAt ?: now) - now) / 60_000), decision.notificationType?.name)
                    stats = stats.copy(reminderCount = stats.reminderCount + 1, zoneId = zone.id, updatedAt = now)
                    dao.saveDailyStats(stats)
                } else {
                    // Permission/channel blocked: back off without counting or claiming a delivery.
                    val blocked = policy.deliveryFailed(task.copy(status = next.status, overdueEnteredAt = next.overdueEnteredAt,
                        expectedFinishReached = next.expectedFinishReached, snoozedUntil = next.snoozedUntil, snoozedFromStatus = next.snoozedFromStatus), now)
                    dao.update(blocked.toEntity())
                    if (task.overdueEnteredAt == null && blocked.overdueEnteredAt != null)
                        writeEvent(task, blocked, TaskEventType.OVERDUE_ENTERED, now, "ENGINE")
                    if (!task.expectedFinishReached && blocked.expectedFinishReached)
                        writeEvent(task, blocked, TaskEventType.EXPECTED_FINISH_REACHED, now, "ENGINE")
                }
            }
        }
    }
    private suspend fun writeEvent(before: Task?, after: Task, type: TaskEventType, at: Long, source: String = "USER", assessment: RiskAssessment? = null, notificationType: String? = null) {
        val payload = kotlinx.serialization.json.Json.parseToJsonElement(eventPayload(before, after, source, assessment)) as kotlinx.serialization.json.JsonObject
        val extra = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        notificationType?.let { extra["notificationType"] = kotlinx.serialization.json.JsonPrimitive(it) }
        if (type == TaskEventType.SNOOZED) extra["minutes"] = kotlinx.serialization.json.JsonPrimitive(((after.snoozedUntil ?: at) - at) / 60_000)
        dao.insertEvent(TaskEventEntity(UUID.randomUUID().toString(), after.id, type, at, kotlinx.serialization.json.JsonObject(payload + extra).toString()))
    }
}

internal fun TaskEntity.toTask() = Task(id, title, goal, category, plannedStartAt, deadlineAt,
    estimatedDurationMinutes, createdAt, updatedAt, status, progress, snoozeCount, nextCheckAt, actualStartAt, completedAt,
    prepareReminderMinutes, reminderMode, prepareReminderSent, startReminderSent, expectedFinishReached, overdueEnteredAt,
    overdueCheckCount, lastCheckAt, snoozedUntil, snoozedFromStatus)
internal fun Task.toEntity() = TaskEntity(id, title, goal, category, plannedStartAt, deadlineAt,
    estimatedDurationMinutes, createdAt, updatedAt, status, progress, snoozeCount, nextCheckAt, actualStartAt, completedAt,
    prepareReminderMinutes, reminderMode, prepareReminderSent, startReminderSent, expectedFinishReached, overdueEnteredAt,
    overdueCheckCount, lastCheckAt, snoozedUntil, snoozedFromStatus)

