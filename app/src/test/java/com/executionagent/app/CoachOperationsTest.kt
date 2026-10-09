package com.executionagent.app

import com.executionagent.app.domain.ai.*
import com.executionagent.app.domain.model.*
import com.executionagent.app.domain.reminder.NoReminderRefresh
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CoachOperationsTest {
    private val clock = FakeClock(Instant.ofEpochMilli(1000000))
    private val task = Task("t", "题目", "三题", "学习", 1, 5000000, 30, 1, 1)
    private class MemoryCoach(var task: Task) : CoachRepository {
        val rows = MutableStateFlow<List<ChatMessage>>(emptyList())
        var commits = 0; var undos = 0
        override fun observe(taskId: String) = rows.map { it.filter { row -> row.taskId == taskId } }
        override suspend fun append(message: ChatMessage) { rows.value += message }
        override suspend fun find(id: String) = rows.value.find { it.id == id }
        override suspend fun commit(id: String, expectedContent: String, before: Task, plan: ActionPlan, content: String, now: Long) {
            require(task == before); require(find(id)?.content == expectedContent)
            task = plan.after; rows.value = rows.value.map { if (it.id == id) it.copy(content = content) else it }; commits++
        }
        override suspend fun undo(id: String, expectedContent: String, proposal: Proposal, content: String, now: Long) {
            require(task == proposal.after); require(find(id)?.content == expectedContent)
            task = proposal.before.copy(updatedAt = now); rows.value = rows.value.map { if (it.id == id) it.copy(content = content) else it }; undos++
        }
    }
    @Test fun proposalDoesNotMutateAndReplayIsIdempotentAcrossRecreation() = runTest {
        val repo = MemoryCoach(task)
        fun ops() = CoachOperations(repo, AgentActionValidator(), clock, NoReminderRefresh)
        val message = ops().propose(task, MockAiProvider().reply(CoachRequest(task, "取消", 1000000)), 1000000)
        assertEquals(task, repo.task); assertEquals(0, repo.commits)
        ops().apply(message.id); ops().apply(message.id)
        assertEquals(1, repo.commits); assertEquals(TaskStatus.CANCELLED, repo.task.status)
        ops().undo(message.id); ops().undo(message.id)
        assertEquals(1, repo.undos); assertEquals(task.goal, repo.task.goal); assertEquals(task.status, repo.task.status)
    }
    @Test fun malformedResponseLeavesNoProposalAndNoMutation() = runTest {
        val repo = MemoryCoach(task); val ops = CoachOperations(repo, AgentActionValidator(), clock, NoReminderRefresh)
        ops.user("t", "下一步")
        try { ops.propose(task, "broken", 1000000); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(1, repo.rows.value.size); assertEquals("user", repo.rows.value.single().role); assertEquals(task, repo.task)
    }
    @Test fun laterEditBlocksStaleProposalAndLaterEditBlocksUndo() = runTest {
        val repo = MemoryCoach(task); val ops = CoachOperations(repo, AgentActionValidator(), clock, NoReminderRefresh)
        val raw = MockAiProvider().reply(CoachRequest(task, "缩小", 1000000))
        val message = ops.propose(task, raw, 1000000)
        repo.task = task.copy(goal = "后来的计划")
        try { ops.apply(message.id); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(0, repo.commits)
        repo.task = task; ops.apply(message.id); repo.task = repo.task.copy(progress = 10)
        try { ops.undo(message.id); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(0, repo.undos); assertEquals(10, repo.task.progress)
    }
    @Test fun corruptedJournalCannotExecuteAndExpiredTimeRevalidates() = runTest {
        val repo = MemoryCoach(task); val ops = CoachOperations(repo, AgentActionValidator(), clock, NoReminderRefresh)
        repo.append(ChatMessage("bad", "t", "user", "{}", 1))
        try { ops.apply("bad"); fail() } catch (_: IllegalArgumentException) { }
        val raw = """{"reply":"重新安排。","strategy":"RESCHEDULE","actions":[{"type":"CHANGE_DEADLINE","taskId":"t","params":{"deadlineAt":1000001}}]}"""
        val message = ops.propose(task, raw, 1000000)
        clock.advance(java.time.Duration.ofSeconds(1))
        try { ops.apply(message.id); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(ProposalState.PENDING, Json.decodeFromString<Proposal>(repo.find(message.id)!!.content).state)
        assertEquals(0, repo.commits)
    }
    @Test fun undoDoesNotReactivateEitherOldNotificationSlot() = runTest {
        val before = task.copy(nextCheckAt = 1000000L + 20 * 60_000)
        val validator = AgentActionValidator()
        val reply = validator.parse(MockAiProvider().reply(CoachRequest(before, "推迟", 1000000)), before, 1000000)
        val after = validator.plan(reply.actions, before, 1000000).after
        val restored = Proposal(reply, before, ProposalState.APPLIED, after).restoredTask(1000000)
        assertNotEquals(before.nextCheckAt, restored.nextCheckAt); assertNotEquals(after.nextCheckAt, restored.nextCheckAt)
        assertEquals(before.deadlineAt, restored.deadlineAt); assertEquals(before.snoozeCount, restored.snoozeCount)
        val tasks = MemoryTaskRepository().apply { create(restored) }
        val ops = com.executionagent.app.domain.usecase.TaskOperations(tasks, clock)
        for (slot in listOf(before.nextCheckAt, after.nextCheckAt)) {
            try { ops.snooze("t", 20, slot); fail() } catch (_: IllegalArgumentException) { }
        }
        assertEquals(restored, tasks.observeTasks().value.single())
    }
}
