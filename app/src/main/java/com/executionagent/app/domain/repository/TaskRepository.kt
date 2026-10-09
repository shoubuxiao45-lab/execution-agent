package com.executionagent.app.domain.repository

import kotlinx.coroutines.flow.Flow
import com.executionagent.app.domain.model.Task
import com.executionagent.app.domain.model.TaskEvent
import com.executionagent.app.domain.model.TaskEventType
import java.time.ZoneId

interface TaskRepository {
    fun observeCount(): Flow<Int>
    fun observeTasks(): Flow<List<Task>>
    suspend fun create(task: Task)
    /** Read and transform the latest row atomically, preventing lost updates. */
    suspend fun update(id: String, eventType: TaskEventType? = null, occurredAt: Long? = null, transform: (Task) -> Task)
    fun observeEvents(id: String): Flow<List<TaskEvent>>
    suspend fun delete(id: String)
    suspend fun processReminders(settings: AgentSettings, now: Long, zone: ZoneId)
    suspend fun remindersToday(now: Long, zone: ZoneId): Int
}
