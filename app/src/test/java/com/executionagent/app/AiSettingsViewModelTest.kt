package com.executionagent.app

import androidx.lifecycle.ViewModelStore
import com.executionagent.app.domain.ai.*
import com.executionagent.app.ui.AiSettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher(); private val store = ViewModelStore()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun finish() { store.clear(); Dispatchers.resetMain() }
    @Test fun clearKeyWorksEvenIfUnsavedDraftConfigurationIsInvalid() = runTest(dispatcher) {
        try {
            val config = object : AiConfigStore { override val config = MutableStateFlow(AiConfig(apiKey = "test-placeholder")); override suspend fun save(config: AiConfig) { config.validated(); this.config.value = config } }
            val vm = AiSettingsViewModel(config).also { store.put("settings", it) }
            vm.save("bad", "", "", clear = true); runCurrent()
            assertTrue(vm.config.value.isMock); assertEquals("已保存", vm.message.value)
        } finally { store.clear() }
    }
    @Test fun storageFailurePreservesConfigurationAndDoesNotExposeErrorDetails() = runTest(dispatcher) {
        try {
            val config = object : AiConfigStore { override val config = MutableStateFlow(AiConfig()); override suspend fun save(config: AiConfig) { error("secret error details") } }
            val vm = AiSettingsViewModel(config).also { store.put("settings", it) }
            vm.save(config.config.value.baseUrl, config.config.value.model, "test-placeholder"); runCurrent()
            assertTrue(vm.config.value.isMock); assertEquals("加密保存失败，请重试", vm.message.value); assertFalse(vm.busy.value)
        } finally { store.clear() }
    }
}
