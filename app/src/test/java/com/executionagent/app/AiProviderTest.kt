package com.executionagent.app

import com.executionagent.app.data.ai.*
import com.executionagent.app.domain.ai.*
import com.executionagent.app.domain.model.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class AiProviderTest {
    private val request = CoachRequest(Task("t", "练习", "做题", "学习", 0, 200000, 20, 0, 0), "开始", 1000)
    @Test fun noKeyUsesMockWithoutNetwork() = runTest {
        val store = object : AiConfigStore { override val config = MutableStateFlow(AiConfig()); override suspend fun save(config: AiConfig) { this.config.value = config } }
        val provider = ConfiguredAiProvider(store, MockAiProvider(), object : KimiTransport {
            override suspend fun complete(config: AiConfig, body: JsonObject): JsonObject = error("unexpected network")
        })
        assertEquals("START_TASK", Json.parseToJsonElement(provider.reply(request)).jsonObject["actions"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
    }
    @Test fun requestContainsBoundedContextAndJsonContract() = runTest {
        var sent: JsonObject? = null
        val transport = object : KimiTransport {
            override suspend fun complete(config: AiConfig, body: JsonObject): JsonObject {
                sent = body
                return Json.parseToJsonElement("""{"choices":[{"finish_reason":"stop","message":{"content":"{}"}}]}""").jsonObject
            }
        }
        assertEquals("{}", KimiProvider(AiConfig(apiKey = "unit-test-placeholder"), transport).reply(request.copy(history = List(20) { AiMessage("user", "hi") })))
        assertEquals(10, sent!!["messages"]!!.jsonArray.size)
        assertEquals("json_object", sent!!["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(sent.toString().contains("unit-test-placeholder"))
    }
    @Test fun ioFailureIsRecoverableAndSanitized() = runTest {
        val provider = KimiProvider(AiConfig(apiKey = "unit-test-placeholder"), object : KimiTransport {
            override suspend fun complete(config: AiConfig, body: JsonObject): JsonObject = throw java.io.IOException("sensitive details")
        })
        repeat(2) { try { provider.reply(request); fail() } catch (failure: AiFailure) { assertFalse(failure.message!!.contains("sensitive")) } }
    }
    @Test fun cancellationPropagates() = runTest {
        val provider = KimiProvider(AiConfig(apiKey = "unit-test-placeholder"), object : KimiTransport {
            override suspend fun complete(config: AiConfig, body: JsonObject): JsonObject = throw CancellationException()
        })
        try { provider.reply(request); fail() } catch (_: CancellationException) { }
    }
    @Test fun rejectsTruncationAndEmptyChoices() = runTest {
        for (response in listOf("""{"choices":[]}""", """{"choices":[{"finish_reason":"length","message":{"content":"{}"}}]}""")) {
            val provider = KimiProvider(AiConfig(apiKey = "unit-test-placeholder"), object : KimiTransport {
                override suspend fun complete(config: AiConfig, body: JsonObject) = Json.parseToJsonElement(response).jsonObject
            })
            try { provider.reply(request); fail() } catch (_: AiFailure) { }
        }
    }
    @Test fun credentialsAreRedactedAndHostIsRestricted() {
        assertFalse(AiConfig(apiKey = "unit-test-placeholder").toString().contains("unit-test-placeholder"))
        for (config in listOf(AiConfig(baseUrl = "http://api.moonshot.cn/v1/"), AiConfig(baseUrl = "https://evil.example/v1/"), AiConfig(apiKey = "bad\nkey"))) {
            assertThrows(IllegalArgumentException::class.java) { config.validated() }
        }
    }
    @Test fun repeatedSnoozeReducesScope() = runTest {
        val result = Json.parseToJsonElement(MockAiProvider().reply(request.copy(task = request.task.copy(snoozeCount = 3)))).jsonObject
        assertEquals("REDUCE_SCOPE", result["strategy"]!!.jsonPrimitive.content)
    }
    @Test fun httpFailuresHaveRecoverableMessagesWithoutResponseDetails() = runTest {
        for (status in listOf(400, 401, 403, 429, 500)) {
            val provider = KimiProvider(AiConfig(apiKey = "unit-test-placeholder"), object : KimiTransport {
                override suspend fun complete(config: AiConfig, body: JsonObject): JsonObject {
                    throw retrofit2.HttpException(retrofit2.Response.error<JsonObject>(status, "sensitive-error-body".toResponseBody()))
                }
            })
            try { provider.reply(request); fail() } catch (failure: AiFailure) { assertFalse(failure.message!!.contains("sensitive")) }
        }
    }
}
