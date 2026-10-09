package com.executionagent.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.executionagent.app.data.local.ExecutionDatabase
import com.executionagent.app.data.local.TaskDao
import com.executionagent.app.data.repository.RoomTaskRepository
import com.executionagent.app.data.settings.DataStoreSettingsRepository
import com.executionagent.app.domain.repository.SettingsRepository
import com.executionagent.app.domain.repository.TaskRepository
import com.executionagent.app.domain.time.ClockProvider
import com.executionagent.app.domain.time.SystemClockProvider
import com.executionagent.app.domain.reminder.ReminderRefresh
import com.executionagent.app.domain.reminder.TaskNotifications
import com.executionagent.app.reminder.ReminderEngine
import com.executionagent.app.reminder.AndroidTaskNotifications
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.settingsStore by preferencesDataStore(name = "agent_settings")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun database(@ApplicationContext context: Context): ExecutionDatabase =
        Room.databaseBuilder(context, ExecutionDatabase::class.java, "execution_agent.db").addMigrations(com.executionagent.app.data.local.TaskMigrations.FROM_1_TO_2).build()
    @Provides fun taskDao(database: ExecutionDatabase): TaskDao = database.taskDao()
    @Provides @Singleton fun settingsStore(@ApplicationContext context: Context): DataStore<Preferences> = context.settingsStore
    @Provides @Singleton fun clock(): ClockProvider = SystemClockProvider()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds @Singleton abstract fun reminderScheduler(scheduler: com.executionagent.app.reminder.AndroidReminderScheduler): com.executionagent.app.domain.reminder.ReminderScheduler
    @Binds @Singleton abstract fun reminderRuntime(runtime: com.executionagent.app.reminder.AndroidReminderRuntime): com.executionagent.app.domain.reminder.ReminderRuntime
    @Binds @Singleton abstract fun profiles(repository: com.executionagent.app.data.repository.RoomProfileRepository): com.executionagent.app.domain.profile.ProfileRepository
    @Binds @Singleton abstract fun coach(repository: com.executionagent.app.data.repository.RoomCoachRepository): com.executionagent.app.domain.ai.CoachRepository
    @Binds @Singleton abstract fun aiConfig(store: com.executionagent.app.data.ai.SecureAiConfigStore): com.executionagent.app.domain.ai.AiConfigStore
    @Binds @Singleton abstract fun aiTransport(transport: com.executionagent.app.data.ai.RetrofitKimiTransport): com.executionagent.app.data.ai.KimiTransport
    @Binds @Singleton abstract fun aiProvider(provider: com.executionagent.app.data.ai.ConfiguredAiProvider): com.executionagent.app.domain.ai.AiProvider
    @Binds @Singleton abstract fun tasks(repository: RoomTaskRepository): TaskRepository
    @Binds @Singleton abstract fun settings(repository: DataStoreSettingsRepository): SettingsRepository
    @Binds @Singleton abstract fun reminderRefresh(engine: ReminderEngine): ReminderRefresh
    @Binds @Singleton abstract fun notifications(implementation: AndroidTaskNotifications): TaskNotifications
}
