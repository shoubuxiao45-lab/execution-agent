package com.executionagent.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.executionagent.app.data.settings.DataStoreSettingsRepository
import com.executionagent.app.domain.repository.AgentSettings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun themeSurvivesStoreRecreation() = runBlocking {
        val file = File(temporaryFolder.root, "test.preferences_pb")
        val firstJob = SupervisorJob()
        val firstScope = CoroutineScope(Dispatchers.IO + firstJob)
        val first = DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = firstScope, produceFile = { file }))
        assertFalse(first.settings.first().darkTheme)
        first.setDarkTheme(true)
        val reminders = AgentSettings(remindersEnabled = false, quietEnabled = false,
            quietStartMinute = 21 * 60, quietEndMinute = 9 * 60, dailyLimit = 7)
        first.setReminderSettings(reminders)
        assertTrue(first.settings.first().darkTheme)
        firstScope.cancel()
        firstJob.join()
        val secondJob = SupervisorJob()
        val secondScope = CoroutineScope(Dispatchers.IO + secondJob)
        try {
            val second = DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = secondScope, produceFile = { file }))
            assertTrue(second.settings.first().darkTheme)
            assertEquals(reminders.copy(darkTheme = true), second.settings.first())
        } finally { secondScope.cancel(); secondJob.join() }
    }
}
