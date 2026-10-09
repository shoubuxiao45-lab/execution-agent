package com.executionagent.app

import com.executionagent.app.domain.model.Task
import com.executionagent.app.domain.model.TaskEvent
import com.executionagent.app.domain.model.TaskEventType
import com.executionagent.app.data.repository.eventPayload
import java.util.UUID
import com.executionagent.app.domain.repository.TaskRepository
import com.executionagent.app.domain.repository.AgentSettings
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class MemoryTaskRepository : TaskRepository {
    private val tasks = MutableStateFlow<List<Task>>(emptyList())
    private val events = MutableStateFlow<List<TaskEvent>>(emptyList())
    override fun observeCount() = tasks.map { it.size }
    override fun observeTasks() = tasks
    override suspend fun create(task: Task) {
        require(tasks.value.none { it.id == task.id })
        tasks.value += task
        events.value += TaskEvent(UUID.randomUUID().toString(), task.id, TaskEventType.CREATED, task.createdAt, eventPayload(null, task, "USER"))
    }
    override suspend fun update(id: String, eventType: TaskEventType?, occurredAt: Long?, transform: (Task) -> Task) {
        val current = requireNotNull(tasks.value.find { it.id == id }) { "任务已不存在" }
        val updated = transform(current)
        tasks.value = tasks.value.map { if (it.id == id) updated else it }
        if (updated != current && eventType != null) {
            events.value += TaskEvent(UUID.randomUUID().toString(), id, eventType, occurredAt ?: updated.updatedAt, eventPayload(current, updated, "USER"))
            if (eventType == TaskEventType.EDITED && (current.plannedStartAt != updated.plannedStartAt || current.deadlineAt != updated.deadlineAt))
                events.value += TaskEvent(UUID.randomUUID().toString(), id, TaskEventType.RESCHEDULED, occurredAt ?: updated.updatedAt, eventPayload(current, updated, "USER"))
        }
    }
    override suspend fun delete(id: String) {
        require(tasks.value.any { it.id == id })
        tasks.value = tasks.value.filterNot { it.id == id }
        events.value = events.value.filterNot { it.taskId == id }
    }
    override suspend fun processReminders(settings: AgentSettings, now: Long, zone: ZoneId) = Unit
    override suspend fun remindersToday(now: Long, zone: ZoneId) = 0
    override fun observeEvents(id: String) = events.map { list -> list.filter { it.taskId == id }.asReversed().take(100) }
}
