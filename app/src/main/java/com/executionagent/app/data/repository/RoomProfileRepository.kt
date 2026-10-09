package com.executionagent.app.data.repository

import androidx.room.Embedded
import androidx.room.Relation
import com.executionagent.app.data.local.*
import com.executionagent.app.domain.model.TaskEvent
import com.executionagent.app.domain.profile.*
import kotlinx.coroutines.flow.map
import javax.inject.Inject

data class LocalExecutionRecord(@Embedded val task: TaskEntity,
    @Relation(parentColumn = "id", entityColumn = "taskId") val events: List<TaskEventEntity>)
class RoomProfileRepository @Inject constructor(database: ExecutionDatabase) : ProfileRepository {
    private val dao = database.taskDao()
    override fun observe() = dao.observeExecutionRecords().map { records ->
        ProfileSnapshot(records.map { it.task.toTask() }, records.flatMap { it.events }.map {
            TaskEvent(it.id, it.taskId, it.type, it.occurredAt, it.payloadJson)
        })
    }
}
