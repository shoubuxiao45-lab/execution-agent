package com.executionagent.app

import androidx.lifecycle.ViewModelStore
import com.executionagent.app.domain.model.Task
import com.executionagent.app.domain.model.TaskDraft
import com.executionagent.app.domain.repository.TaskRepository
import com.executionagent.app.domain.usecase.TaskOperations
import com.executionagent.app.ui.TaskViewModel
import java.time.Instant
import java.time.Duration
import com.executionagent.app.domain.engine.RiskLevel
import com.executionagent.app.ui.TaskAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val clock = FakeClock(Instant.parse("2026-10-06T02:00:00Z"))
    private fun draft() = TaskDraft("电路", "第一题", "学习", clock.now().toEpochMilli(), clock.now().plusSeconds(60).toEpochMilli(), 15)
    private fun viewModel(repository: TaskRepository): TaskViewModel =
        TaskViewModel(TaskOperations(repository, clock)).also { store.put("tasks", it) }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }
    private fun vmTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        // Cancel the perpetual refresh before runTest advances remaining scheduled work.
        try { block() } finally { store.clear() }
    }

    @Test fun duplicateRapidSaveCreatesOnlyOneTaskAndReturnsAfterSuccess() = vmTest {
        val repository = MemoryTaskRepository()
        val vm = viewModel(repository)
        runCurrent()
        assertTrue(vm.state.value.loaded)
        vm.save(null, draft())
        vm.save(null, draft())
        assertTrue(vm.state.value.busy)
        runCurrent()
        assertEquals(1, repository.observeCount().first())
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.error)
        assertEquals(Unit, vm.returnToToday.first())
    }

    @Test fun storageFailureIsVisibleAndNextSaveCanRecover() = vmTest {
        val memory = MemoryTaskRepository()
        var fail = true
        val repository = object : TaskRepository by memory {
            override suspend fun create(task: Task) {
                if (fail) throw IllegalStateException("storage unavailable")
                memory.create(task)
            }
        }
        val vm = viewModel(repository)
        runCurrent()
        vm.save(null, draft())
        runCurrent()
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.busy)
        assertEquals(0, memory.observeCount().first())
        fail = false
        vm.save(null, draft())
        runCurrent()
        assertNull(vm.state.value.error)
        assertEquals(1, memory.observeCount().first())
    }

    @Test fun failedLoadRemainsDistinctFromEmptyDatabaseAndRetryRecovers() = vmTest {
        val memory = MemoryTaskRepository()
        var fail = true
        val repository = object : TaskRepository by memory {
            override fun observeTasks(): Flow<List<Task>> =
                if (fail) flow { throw IllegalStateException("read failed") } else memory.observeTasks()
        }
        val vm = viewModel(repository)
        runCurrent()
        assertFalse(vm.state.value.loaded)
        assertNotNull(vm.state.value.loadError)
        fail = false
        vm.retry()
        runCurrent()
        assertTrue(vm.state.value.loaded)
        assertNull(vm.state.value.loadError)
    }
    @Test fun displayedRiskRefreshesWithTimeAndCompletionClearsIt() = vmTest {
        val repository = MemoryTaskRepository()
        val vm = viewModel(repository)
        vm.save(null, draft())
        runCurrent()
        val id = repository.observeTasks().first().single().id
        assertEquals(RiskLevel.MEDIUM, vm.state.value.risks.getValue(id).level)
        clock.advance(Duration.ofHours(1))
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(RiskLevel.HIGH, vm.state.value.risks.getValue(id).level)
        vm.act(id, TaskAction.COMPLETE)
        runCurrent()
        assertEquals(0, vm.state.value.risks.getValue(id).score)
    }
}
