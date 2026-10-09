package com.executionagent.app.data.repository

import androidx.room.withTransaction
import com.executionagent.app.data.local.*
import com.executionagent.app.domain.ai.*
import com.executionagent.app.domain.model.*
import com.executionagent.app.domain.reminder.TaskNotifications
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.*
import java.util.UUID
import javax.inject.Inject

class RoomCoachRepository @Inject constructor(private val database: ExecutionDatabase, private val notifications: TaskNotifications) : CoachRepository {
    private val dao = database.taskDao()
    override fun observe(taskId: String) = dao.observeConversation(taskId).map { rows -> rows.reversed().map { it.message() } }
    override suspend fun append(message: ChatMessage) { dao.insertConversation(ConversationEntity(message.id, message.taskId, message.role, message.content, message.createdAt)) }
    override suspend fun find(id: String) = dao.conversation(id)?.message()
    override suspend fun commit(id: String, expectedContent: String, before: Task, plan: ActionPlan, content: String, now: Long) {
        val strategy = Json.decodeFromString<Proposal>(content).reply.strategy
        database.withTransaction {
            require(dao.find(before.id)?.toTask() == before) { "任务已变化，请重新生成建议" }
            check(dao.replaceConversation(id, expectedContent, content) == 1) { "建议已处理，请刷新" }
            check(dao.update(plan.after.toEntity()) == 1)
            plan.events.forEach { event(before, plan.after, it, now, id, "APPLY", strategy) }
            plan.children.forEach { child -> dao.insert(child.toEntity()); event(null, child, TaskEventType.CREATED, now, id, "SPLIT_CHILD") }
            event(before, plan.after, TaskEventType.AI_INTERVENTION, now, id, "APPLY", strategy)
            notifications.cancel(before.id)
        }
    }
    override suspend fun undo(id: String, expectedContent: String, proposal: Proposal, content: String, now: Long) {
        database.withTransaction {
            val after = requireNotNull(proposal.after)
            require(dao.find(after.id)?.toTask() == after) { "任务已再次变化，不能覆盖后续操作" }
            proposal.children.forEach { child ->
                require(dao.find(child.id)?.toTask() == child && dao.eventCount(child.id) == 1 && dao.conversationCount(child.id) == 0) { "子任务已有变化，不能撤销拆分" }
            }
            check(dao.replaceConversation(id, expectedContent, content) == 1) { "建议已处理，请刷新" }
            val restored = proposal.restoredTask(now)
            check(dao.update(restored.toEntity()) == 1)
            proposal.children.forEach { child -> check(dao.delete(child.id) == 1); notifications.cancel(child.id) }
            event(after, restored, TaskEventType.AI_INTERVENTION, now, id, "UNDO", proposal.reply.strategy)
            notifications.cancel(after.id)
        }
    }
    private suspend fun event(before: Task?, after: Task, type: TaskEventType, at: Long, proposalId: String, operation: String, strategy: String? = null) {
        val payload = Json.parseToJsonElement(eventPayload(before, after, "AI")).jsonObject
        val extended = JsonObject(payload + mapOf("proposalId" to JsonPrimitive(proposalId), "operation" to JsonPrimitive(operation)) +
            (strategy?.let { mapOf("strategy" to JsonPrimitive(it)) } ?: emptyMap()))
        dao.insertEvent(TaskEventEntity(UUID.randomUUID().toString(), after.id, type, at, extended.toString()))
    }
}
private fun ConversationEntity.message() = ChatMessage(id, taskId, role, content, createdAt)
