package com.executionagent.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.executionagent.app.domain.model.Task
import com.executionagent.app.domain.model.TaskDraft
import com.executionagent.app.domain.usecase.TaskOperations
import com.executionagent.app.domain.engine.ExecutionEngine
import com.executionagent.app.domain.engine.RiskAssessment
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TaskUiState(
    val tasks: List<Task> = emptyList(),
    val loaded: Boolean = false,
    val loadError: String? = null,
    val error: String? = null,
    val busy: Boolean = false,
    val now: Long = 0,
    val risks: Map<String, RiskAssessment> = emptyMap()
)

enum class TaskAction { START, PROGRESS, COMPLETE, CANCEL, DELETE, SNOOZE }

@HiltViewModel
class TaskViewModel @Inject constructor(private val operations: TaskOperations, private val execution: ExecutionEngine = ExecutionEngine()) : ViewModel() {
    private val mutableState = MutableStateFlow(TaskUiState(now = operations.now()))
    val state = mutableState.asStateFlow()
    val zoneId get() = operations.zoneId()
    fun events(id: String) = operations.observeEvents(id)
    private val returnEvents = Channel<Unit>(Channel.BUFFERED)
    val returnToToday = returnEvents.receiveAsFlow()
    private var observeJob: Job? = null

    init {
        retry()
        viewModelScope.launch {
            while (true) {
                mutableState.update { present(it) }
                delay(30_000)
            }
        }
    }

    fun retry() {
        observeJob?.cancel()
        mutableState.update { it.copy(loadError = null) }
        observeJob = viewModelScope.launch {
            try {
                operations.observeTasks().collect { tasks ->
                    mutableState.update { present(it.copy(tasks = tasks, loaded = true, loadError = null)) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(loadError = "读取任务失败，请重试") } }
        }
    }

    fun save(id: String?, draft: TaskDraft) = execute(returnAfter = true) { operations.save(id, draft) }
    fun act(id: String, action: TaskAction, value: Int = 0) = execute(returnAfter = action == TaskAction.DELETE) {
        when (action) {
            TaskAction.START -> operations.start(id, renewGrace = true)
            TaskAction.PROGRESS -> operations.progress(id, value)
            TaskAction.COMPLETE -> operations.complete(id)
            TaskAction.CANCEL -> operations.cancel(id)
            TaskAction.DELETE -> operations.delete(id)
            TaskAction.SNOOZE -> operations.snooze(id, value)
        }
    }

    fun clearError() { mutableState.update { it.copy(error = null) } }
    private fun execute(returnAfter: Boolean, block: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
                if (returnAfter) returnEvents.send(Unit)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (invalid: IllegalArgumentException) { mutableState.update { it.copy(error = invalid.message) } }
            catch (_: Exception) { mutableState.update { it.copy(error = "操作失败，数据未能保存，请重试") } }
            finally { mutableState.update { present(it.copy(busy = false)) } }
        }
    }
    private fun present(state: TaskUiState): TaskUiState {
        val now = operations.now()
        return state.copy(now = now, risks = state.tasks.associate { it.id to execution.assess(it, now) })
    }
}
