package com.executionagent.app.domain.ai

import com.executionagent.app.domain.model.Task
import com.executionagent.app.domain.reminder.ReminderRefresh
import com.executionagent.app.domain.time.ClockProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

@Serializable enum class ProposalState { PENDING, APPLIED, UNDONE }
@Serializable data class Proposal(val reply: CoachReply, val before: Task, val state: ProposalState = ProposalState.PENDING,
    val after: Task? = null, val children: List<Task> = emptyList(), val appliedAt: Long? = null)
// Undo restores timing while minting a new reminder slot, so queued old notification buttons stay stale.
internal fun Proposal.restoredTask(now: Long): Task {
    val applied = requireNotNull(after)
    val reminder = before.nextCheckAt?.let { previous ->
        var fresh = previous + 1
        if (fresh == applied.nextCheckAt) fresh++
        fresh
    }
    return before.copy(updatedAt = maxOf(now, applied.updatedAt + 1), nextCheckAt = reminder)
}
data class ChatMessage(val id: String, val taskId: String, val role: String, val content: String, val createdAt: Long)
interface CoachRepository {
    fun observe(taskId: String): Flow<List<ChatMessage>>
    suspend fun append(message: ChatMessage)
    suspend fun find(id: String): ChatMessage?
    suspend fun commit(id: String, expectedContent: String, before: Task, plan: ActionPlan, content: String, now: Long)
    suspend fun undo(id: String, expectedContent: String, proposal: Proposal, content: String, now: Long)
}
class CoachOperations @Inject constructor(private val repository: CoachRepository, private val validator: AgentActionValidator,
    private val clock: ClockProvider, private val reminders: ReminderRefresh) {
    fun observe(id: String) = repository.observe(id)
    suspend fun findMessage(id: String) = repository.find(id)
    suspend fun propose(task: Task, raw: String, at: Long): ChatMessage {
        val reply = validator.parse(raw, task, at)
        val message = ChatMessage(UUID.randomUUID().toString(), task.id, "proposal", Json.encodeToString(Proposal(reply, task)), at)
        repository.append(message)
        return message
    }
    suspend fun user(taskId: String, text: String): ChatMessage {
        require(text.isNotBlank() && text.length <= 2000) { "消息须为1–2000字符" }
        return ChatMessage(UUID.randomUUID().toString(), taskId, "user", text.trim(), clock.now().toEpochMilli()).also { repository.append(it) }
    }
    suspend fun apply(id: String) {
        val message = requireNotNull(repository.find(id)) { "建议已不存在" }
        require(message.role == "proposal") { "不是动作建议" }
        val proposal = Json.decodeFromString<Proposal>(message.content)
        if (proposal.state == ProposalState.APPLIED) return
        require(proposal.state == ProposalState.PENDING && proposal.reply.actions.isNotEmpty()) { "建议不能应用" }
        val now = clock.now().toEpochMilli()
        val plan = validator.plan(proposal.reply.actions, proposal.before, now)
        val applied = proposal.copy(state = ProposalState.APPLIED, after = plan.after, children = plan.children, appliedAt = now)
        repository.commit(id, message.content, proposal.before, plan, Json.encodeToString(applied), now)
        reminders.refresh()
    }
    suspend fun undo(id: String) {
        val message = requireNotNull(repository.find(id)) { "建议已不存在" }
        require(message.role == "proposal") { "不是动作建议" }
        val proposal = Json.decodeFromString<Proposal>(message.content)
        if (proposal.state == ProposalState.UNDONE) return
        require(proposal.state == ProposalState.APPLIED) { "建议尚未应用" }
        repository.undo(id, message.content, proposal, Json.encodeToString(proposal.copy(state = ProposalState.UNDONE)), clock.now().toEpochMilli())
        reminders.refresh()
    }
}
