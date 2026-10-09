package com.executionagent.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import com.executionagent.app.domain.repository.AgentSettings
import com.executionagent.app.domain.repository.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.map

class DataStoreSettingsRepository @Inject constructor(private val store: DataStore<Preferences>) : SettingsRepository {
    override val settings = store.data.map {
        AgentSettings(it[DARK_THEME] ?: false, it[ENABLED] ?: true, it[QUIET] ?: true,
            it[QUIET_START] ?: 1320, it[QUIET_END] ?: 480, it[LIMIT] ?: 5)
    }
    override suspend fun setDarkTheme(enabled: Boolean) { store.edit { it[DARK_THEME] = enabled } }
    override suspend fun setReminderSettings(settings: AgentSettings) {
        val clean = settings.validated()
        store.edit {
            it[ENABLED] = clean.remindersEnabled; it[QUIET] = clean.quietEnabled
            it[QUIET_START] = clean.quietStartMinute; it[QUIET_END] = clean.quietEndMinute
            it[LIMIT] = clean.dailyLimit
        }
    }
    private companion object {
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val ENABLED = booleanPreferencesKey("reminders_enabled")
        val QUIET = booleanPreferencesKey("quiet_enabled")
        val QUIET_START = intPreferencesKey("quiet_start")
        val QUIET_END = intPreferencesKey("quiet_end")
        val LIMIT = intPreferencesKey("daily_limit")
    }
}
