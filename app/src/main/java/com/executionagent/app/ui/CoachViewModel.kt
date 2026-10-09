package com.executionagent.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.executionagent.app.domain.ai.*
import com.executionagent.app.domain.repository.TaskRepository
import com.executionagent.app.domain.time.ClockProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

data class CoachState(val taskId: String? = null, val messages: List<ChatMessage> = emptyList(), val input: String = "",
    val busy: Boolean = false, val error: String? = null, val loadError: String? = null)
@HiltViewModel
class CoachViewModel @Inject constructor(private val operations: CoachOperations, private val tasks: TaskRepository,
    private val ai: AiProvider, private val clock: ClockProvider, config: AiConfigStore,
    private val saved: SavedStateHandle = SavedStateHandle()) : ViewModel() {
    private val mutable = MutableStateFlow(CoachState(taskId = saved["coachTaskId"], input = saved["coachDraft"] ?: ""))
    val state = mutable.asStateFlow()
    val config = config.config
    private var observeJob: Job? = null
    private var pendingUser: ChatMessage? = null
    fun select(id: String) {
        if (mutable.value.busy || (observeJob != null && mutable.value.taskId == id && mutable.value.loadError == null)) return
        val draft = mutable.value.input.takeIf { mutable.value.taskId == id } ?: ""
        observeJob?.cancel(); pendingUser = null
        if (mutable.value.taskId != id) saved.remove<String>("coachPendingUser")
        saved["coachTaskId"] = id; saved["coachDraft"] = draft
        mutable.value = CoachState(taskId = id, input = draft)
        observeJob = viewModelScope.launch {
            try { operations.observe(id).collect { messages -> mutable.update { it.copy(messages = messages, loadError = null) } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(loadError = "读取聊天失败，请重试") } }
        }
    }
    fun input(value: String) { if (!mutable.value.busy) {
        saved["coachDraft"] = value.take(2000)
        mutable.update { it.copy(input = value.take(2000), error = null) }
    } }
    fun send() = execute {
        val id = requireNotNull(mutable.value.taskId) { "先选择任务" }
        val text = mutable.value.input.trim()
        require(text.isNotBlank()) { "请输入消息" }
        val task = requireNotNull(tasks.observeTasks().first().find { it.id == id }) { "任务已不存在" }
        if (pendingUser == null) pendingUser = saved.get<String>("coachPendingUser")?.let { operations.findMessage(it) }
        val history = mutable.value.messages.filter { it.id != pendingUser?.id }.takeLast(8).mapNotNull { message ->
            when (message.role) {
                "user" -> AiMessage("user", message.content)
                "proposal" -> runCatching { AiMessage("assistant", Json.decodeFromString<Proposal>(message.content).reply.reply) }.getOrNull()
                else -> null
            }
        }
        if (pendingUser?.role != "user" || pendingUser?.taskId != id || pendingUser?.content != text) pendingUser = operations.user(id, text)
        saved["coachPendingUser"] = pendingUser?.id
        val at = clock.now().toEpochMilli()
        val events = tasks.observeEvents(id).first().take(10).map { "${it.type.name}@${it.occurredAt}" }
        val raw = ai.reply(CoachRequest(task, text, at, history, events))
        operations.propose(task, raw, at)
        pendingUser = null
        saved.remove<String>("coachPendingUser"); saved["coachDraft"] = ""
        mutable.update { it.copy(input = "") }
    }
    fun apply(id: String) = execute { operations.apply(id) }
    fun undo(id: String) = execute { operations.undo(id) }
    private fun execute(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: AiFailure) { mutable.update { it.copy(error = failure.message) } }
            catch (invalid: IllegalArgumentException) { mutable.update { it.copy(error = invalid.message) } }
            catch (_: Exception) { mutable.update { it.copy(error = "操作失败，请重试；已提交建议不会重复执行") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
