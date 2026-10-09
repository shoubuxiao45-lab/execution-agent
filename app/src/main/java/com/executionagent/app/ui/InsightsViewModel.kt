package com.executionagent.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.executionagent.app.domain.profile.*
import com.executionagent.app.domain.time.ClockProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InsightsState(val days: Int = 7, val profile: ExecutionProfile? = null, val error: String? = null)
@HiltViewModel
class InsightsViewModel @Inject constructor(private val repository: ProfileRepository, private val calculator: ExecutionProfileCalculator,
    private val clock: ClockProvider) : ViewModel() {
    private val mutable = MutableStateFlow(InsightsState())
    val state = mutable.asStateFlow()
    private var snapshot: ProfileSnapshot? = null
    private var job: Job? = null
    init {
        retry()
        viewModelScope.launch { while (true) { recalculate(); delay(30_000) } }
    }
    fun window(days: Int) { require(days == 7 || days == 30); mutable.update { it.copy(days = days) }; recalculate() }
    fun retry() {
        job?.cancel(); mutable.update { it.copy(error = null) }
        job = viewModelScope.launch {
            try { repository.observe().collect { snapshot = it; recalculate() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(error = "读取执行记录失败，请重试") } }
        }
    }
    private fun recalculate() {
        snapshot?.let { input -> mutable.update { it.copy(profile = calculator.calculate(input, it.days, clock.now().toEpochMilli(), clock.zoneId())) } }
    }
}
