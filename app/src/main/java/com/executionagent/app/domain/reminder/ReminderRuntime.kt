package com.executionagent.app.domain.reminder

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AlarmMode { NONE, EXACT, INEXACT }
data class ReminderRuntimeState(val nextAlarmAt: Long? = null, val alarmMode: AlarmMode = AlarmMode.NONE,
    val lastScanAt: Long? = null, val lastError: String? = null)
interface ReminderScheduler { fun schedule(at: Long?); fun retryLater() }
interface ReminderRuntime {
    val state: StateFlow<ReminderRuntimeState>
    fun scheduled(at: Long?, mode: AlarmMode)
    fun scanned(at: Long)
    fun failed()
}
object NoReminderRuntime : ReminderRuntime {
    override val state = MutableStateFlow(ReminderRuntimeState())
    override fun scheduled(at: Long?, mode: AlarmMode) = Unit
    override fun scanned(at: Long) = Unit
    override fun failed() = Unit
}
