package com.executionagent.app.domain.repository

import kotlinx.coroutines.flow.Flow

data class AgentSettings(
    val darkTheme: Boolean = false,
    val remindersEnabled: Boolean = true,
    val quietEnabled: Boolean = true,
    val quietStartMinute: Int = 22 * 60,
    val quietEndMinute: Int = 8 * 60,
    val dailyLimit: Int = 5
) {
    fun validated(): AgentSettings {
        require(quietStartMinute in 0..1439 && quietEndMinute in 0..1439) { "安静时间无效" }
        require(quietStartMinute != quietEndMinute) { "安静时段的开始和结束须不同" }
        require(dailyLimit in 1..20) { "每天提醒上限须为 1–20 次" }
        return this
    }
}
interface SettingsRepository {
    val settings: Flow<AgentSettings>
    suspend fun setDarkTheme(enabled: Boolean)
    suspend fun setReminderSettings(settings: AgentSettings)
}
