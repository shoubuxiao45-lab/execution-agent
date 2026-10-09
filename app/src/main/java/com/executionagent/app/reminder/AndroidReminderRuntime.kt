package com.executionagent.app.reminder

import android.content.Context
import androidx.core.content.edit
import com.executionagent.app.domain.reminder.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidReminderRuntime @Inject constructor(@ApplicationContext context: Context) : ReminderRuntime {
    private val prefs = context.getSharedPreferences("reminder_runtime", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(ReminderRuntimeState(
        prefs.getLong("nextAlarmAt", 0).takeIf { it > 0 },
        runCatching { AlarmMode.valueOf(prefs.getString("mode", null) ?: "NONE") }.getOrDefault(AlarmMode.NONE),
        prefs.getLong("lastScanAt", 0).takeIf { it > 0 }, prefs.getString("error", null)))
    override val state = mutable.asStateFlow()
    @Synchronized override fun scheduled(at: Long?, mode: AlarmMode) { save(mutable.value.copy(nextAlarmAt = at, alarmMode = mode)) }
    @Synchronized override fun scanned(at: Long) { save(mutable.value.copy(lastScanAt = at, lastError = null)) }
    @Synchronized override fun failed() { save(mutable.value.copy(lastError = "上次扫描或调度失败，系统将重试")) }
    private fun save(state: ReminderRuntimeState) {
        // Diagnostic snapshots are not the task/quota source of truth; asynchronous persistence is sufficient.
        prefs.edit { putLong("nextAlarmAt", state.nextAlarmAt ?: 0); putString("mode", state.alarmMode.name)
            putLong("lastScanAt", state.lastScanAt ?: 0); putString("error", state.lastError) }
        mutable.value = state
    }
}
