package com.executionagent.app

import androidx.lifecycle.ViewModelStore
import com.executionagent.app.domain.model.Task
import com.executionagent.app.domain.profile.*
import com.executionagent.app.ui.InsightsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val clock = FakeClock(Instant.parse("2026-10-06T12:00:00Z"))
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun finish() { store.clear(); Dispatchers.resetMain() }
    @Test fun profileReactsToWindowDataAndClock() = runTest(dispatcher) {
        try {
            val now = clock.now().toEpochMilli(); val data = MutableStateFlow(ProfileSnapshot(emptyList(), emptyList()))
            val vm = InsightsViewModel(object : ProfileRepository { override fun observe() = data }, ExecutionProfileCalculator(), clock).also { store.put("insights", it) }
            runCurrent(); assertEquals(0, vm.state.value.profile!!.cohort)
            data.value = ProfileSnapshot(listOf(Task("t", "任务", "", "", now - 1000, now, 30, now - 1000, now)), emptyList())
            runCurrent(); assertEquals(1, vm.state.value.profile!!.cohort)
            vm.window(30); assertEquals(30, vm.state.value.profile!!.trends.size)
            clock.advance(Duration.ofDays(1)); advanceTimeBy(30_000); runCurrent()
            assertEquals("2026-09-08", vm.state.value.profile!!.start.toString())
        } finally { store.clear() }
    }
    @Test fun failedReadIsVisibleAndCanBeRetried() = runTest(dispatcher) {
        try {
            var broken = true
            val repository = object : ProfileRepository { override fun observe() = flow { if (broken) error("disk"); emit(ProfileSnapshot(emptyList(), emptyList())) } }
            val vm = InsightsViewModel(repository, ExecutionProfileCalculator(), clock).also { store.put("insights", it) }
            runCurrent(); assertNotNull(vm.state.value.error)
            broken = false; vm.retry(); runCurrent(); assertNull(vm.state.value.error); assertNotNull(vm.state.value.profile)
        } finally { store.clear() }
    }
}
