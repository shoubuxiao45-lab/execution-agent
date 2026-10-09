package com.executionagent.app

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.SavedStateHandle
import com.executionagent.app.domain.ai.*
import com.executionagent.app.domain.model.Task
import com.executionagent.app.domain.reminder.NoReminderRefresh
import com.executionagent.app.ui.CoachViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class CoachViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val clock = FakeClock(Instant.ofEpochMilli(1000000))
    private val task = Task("t", "作业", "三题", "学习", 1, 5000000, 30, 1, 1)
    private val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val repository = object : CoachRepository {
        override fun observe(taskId: String) = messages.map { it.filter { message -> message.taskId == taskId } }
        override suspend fun append(message: ChatMessage) { messages.value += message }
        override suspend fun find(id: String) = messages.value.find { it.id == id }
        override suspend fun commit(id: String, expectedContent: String, before: Task, plan: ActionPlan, content: String, now: Long) = error("unexpected mutation")
        override suspend fun undo(id: String, expectedContent: String, proposal: Proposal, content: String, now: Long) = error("unexpected mutation")
    }
    private val config = object : AiConfigStore { override val config = MutableStateFlow(AiConfig()); override suspend fun save(config: AiConfig) { this.config.value = config } }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun finish() { store.clear(); Dispatchers.resetMain() }
    @Test fun duplicateSendAndNetworkRetryPreserveInputWithoutDuplicatingUserRow() = runTest(dispatcher) {
        try {
            val tasks = MemoryTaskRepository().apply { create(task) }; var calls = 0
            val ai = object : AiProvider {
                override suspend fun reply(request: CoachRequest): String {
                    calls++; if (calls == 1) throw AiFailure("网络连接失败，请重试")
                    return MockAiProvider().reply(request)
                }
            }
            val vm = CoachViewModel(CoachOperations(repository, AgentActionValidator(), clock, NoReminderRefresh), tasks, ai, clock, config).also { store.put("coach", it) }
            vm.select("t"); runCurrent(); vm.input("缩小"); vm.send(); vm.send(); runCurrent()
            assertEquals(1, calls); assertEquals("缩小", vm.state.value.input); assertNotNull(vm.state.value.error)
            vm.send(); runCurrent()
            assertEquals(2, calls); assertEquals(1, messages.value.count { it.role == "user" }); assertEquals(1, messages.value.count { it.role == "proposal" })
            assertEquals("", vm.state.value.input); assertNull(vm.state.value.error); assertEquals(task, tasks.observeTasks().value.single())
        } finally { store.clear() }
    }
    @Test fun malformedAiReplyStaysRecoverableWithoutProposal() = runTest(dispatcher) {
        try {
            val tasks = MemoryTaskRepository().apply { create(task) }
            val vm = CoachViewModel(CoachOperations(repository, AgentActionValidator(), clock, NoReminderRefresh), tasks,
                object : AiProvider { override suspend fun reply(request: CoachRequest) = "{bad}" }, clock, config).also { store.put("coach", it) }
            vm.select("t"); runCurrent(); vm.input("开始"); vm.send(); runCurrent()
            assertNotNull(vm.state.value.error); assertFalse(vm.state.value.busy)
            assertEquals("开始", vm.state.value.input); assertEquals(0, messages.value.count { it.role == "proposal" })
        } finally { store.clear() }
    }
    @Test fun processStateRestoresDraftAndPendingUserWithoutDuplicateHistory() = runTest(dispatcher) {
        try {
            val tasks = MemoryTaskRepository().apply { create(task) }; val saved = SavedStateHandle()
            val operations = CoachOperations(repository, AgentActionValidator(), clock, NoReminderRefresh)
            val first = CoachViewModel(operations, tasks, object : AiProvider { override suspend fun reply(request: CoachRequest): String = throw AiFailure("offline") }, clock, config, saved).also { store.put("first", it) }
            first.select("t"); runCurrent(); first.input("缩小"); first.send(); runCurrent()
            val restored = SavedStateHandle(mapOf("coachTaskId" to saved.get<String>("coachTaskId"), "coachDraft" to saved.get<String>("coachDraft"), "coachPendingUser" to saved.get<String>("coachPendingUser")))
            store.clear()
            val second = CoachViewModel(operations, tasks, MockAiProvider(), clock, config, restored).also { store.put("second", it) }
            second.select("t"); runCurrent(); assertEquals("缩小", second.state.value.input)
            second.send(); runCurrent(); assertEquals(1, messages.value.count { it.role == "user" }); assertEquals(1, messages.value.count { it.role == "proposal" })
            assertEquals("", restored.get<String>("coachDraft"))
        } finally { store.clear() }
    }
}
