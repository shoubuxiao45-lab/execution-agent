package com.executionagent.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.executionagent.app.domain.repository.AgentSettings
import com.executionagent.app.domain.repository.SettingsRepository
import com.executionagent.app.domain.reminder.ReminderRefresh
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AppViewModel @Inject constructor(private val repository: SettingsRepository, private val reminders: ReminderRefresh) : ViewModel() {
    val settings = repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentSettings())
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    fun setDarkTheme(enabled: Boolean) = save { repository.setDarkTheme(enabled) }
    fun setReminderSettings(config: AgentSettings) = save { repository.setReminderSettings(config); reminders.refresh() }
    private fun save(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        error.value = null
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (invalid: IllegalArgumentException) { error.value = invalid.message }
            catch (_: Exception) { error.value = "设置保存失败，请重试" }
            finally { busy.value = false }
        }
    }
}
