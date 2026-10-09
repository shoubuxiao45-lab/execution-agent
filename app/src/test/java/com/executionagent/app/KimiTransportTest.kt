package com.executionagent.app

import com.executionagent.app.data.ai.*
import com.executionagent.app.domain.ai.*
import com.executionagent.app.domain.model.Task
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class KimiTransportTest {
    private val config = AiConfig(apiKey = "transport-test-placeholder")
    private val request = CoachRequest(Task("t", "题目", "", "", 0, 200000, 10, 0, 0), "开始", 1000)
    @Test fun retrofitSerializesJsonAndAuthorizationWithoutRealApiCall() = runTest {
        var body = ""; var authorization = ""; var url = ""
        val client = kimiHttpClient().newBuilder().addInterceptor { chain ->
            val req = chain.request(); url = req.url.toString(); authorization = req.header("Authorization").orEmpty()
            val buffer = Buffer(); req.body!!.writeTo(buffer); body = buffer.readUtf8()
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"choices":[{"finish_reason":"stop","message":{"content":"{}"}}]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        assertEquals("{}", KimiProvider(config, RetrofitKimiTransport.withClient(client)).reply(request))
        assertEquals("https://api.moonshot.cn/v1/chat/completions", url); assertEquals("Bearer transport-test-placeholder", authorization)
        assertEquals("kimi-k2.6", Json.parseToJsonElement(body).jsonObject["model"]!!.jsonPrimitive.content)
        assertFalse(body.contains("transport-test-placeholder"))
    }
    @Test fun realConverterRejectsMalformedBodyAndNextCallRecovers() = runTest {
        var calls = 0
        val client = kimiHttpClient().newBuilder().addInterceptor { chain ->
            calls++; val body = if (calls == 1) "{bad}" else """{"choices":[{"finish_reason":"stop","message":{"content":"{}"}}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val provider = KimiProvider(config, RetrofitKimiTransport.withClient(client))
        try { provider.reply(request); fail() } catch (_: AiFailure) { }
        assertEquals("{}", provider.reply(request)); assertEquals(2, calls)
    }
    @Test fun redirectAndAutomaticRetryAreDisabledAndTimeoutIsBounded() {
        val client = kimiHttpClient()
        assertFalse(client.followRedirects); assertFalse(client.followSslRedirects); assertFalse(client.retryOnConnectionFailure)
        assertEquals(75_000, client.callTimeoutMillis); assertEquals(15_000, client.connectTimeoutMillis); assertEquals(60_000, client.readTimeoutMillis)
    }
    @Test fun ioFailurePassesThroughActualTransportAsRecoverableError() = runTest {
        for (error in listOf(java.io.IOException("private details"), java.net.SocketTimeoutException("private timeout"))) {
            val client = kimiHttpClient().newBuilder().addInterceptor { throw error }.build()
            try { KimiProvider(config, RetrofitKimiTransport.withClient(client)).reply(request); fail() }
            catch (failure: AiFailure) {
                assertEquals("网络连接失败，请检查网络后重试", failure.message)
                assertFalse(failure.message!!.contains("private"))
            }
        }
    }
}
