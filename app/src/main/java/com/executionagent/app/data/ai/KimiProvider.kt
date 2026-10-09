package com.executionagent.app.data.ai

import com.executionagent.app.domain.ai.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.HttpException
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

interface KimiTransport { suspend fun complete(config: AiConfig, body: JsonObject): JsonObject }
interface KimiApi {
    @POST("chat/completions") suspend fun complete(@Header("Authorization") authorization: String, @Body body: JsonObject): JsonObject
}
@Singleton
class RetrofitKimiTransport private constructor(private val client: OkHttpClient) : KimiTransport {
    @Inject constructor() : this(kimiHttpClient())
    private val json = Json { ignoreUnknownKeys = true }
    companion object { internal fun withClient(client: OkHttpClient) = RetrofitKimiTransport(client) }
    override suspend fun complete(config: AiConfig, body: JsonObject): JsonObject {
        config.validated()
        return Retrofit.Builder().baseUrl(config.baseUrl).client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build().create(KimiApi::class.java).complete("Bearer ${config.apiKey}", body)
    }
}
internal fun kimiHttpClient(): OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
    .callTimeout(75, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
class KimiProvider(private val config: AiConfig, private val transport: KimiTransport) : AiProvider {
    override suspend fun reply(request: CoachRequest): String {
        try {
            val body = buildJsonObject {
                put("model", config.model); put("stream", false); put("max_tokens", 2048)
                if (config.model == "kimi-k2.6") put("thinking", buildJsonObject { put("type", "disabled") })
                put("response_format", buildJsonObject { put("type", "json_object") })
                put("messages", buildJsonArray {
                    fun message(role: String, content: String) { add(buildJsonObject { put("role", role); put("content", content) }) }
                    message("system", CoachPrompt.system)
                    request.history.filter { it.role == "user" || it.role == "assistant" }.takeLast(8).forEach { message(it.role, it.content.take(2000)) }
                    message("user", CoachPrompt.context(request))
                })
            }
            val choice = transport.complete(config.validated(), body)["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw AiFailure("Kimi 返回了空回复，请重试")
            if (choice["finish_reason"]?.jsonPrimitive?.content != "stop") throw AiFailure("回复未完整生成，请重试或调整模型")
            val content = choice["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: throw AiFailure("Kimi 返回了空回复，请重试")
            if (content.isBlank() || content.length > 16_000) throw AiFailure("回复长度无效，请重试")
            return content
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: AiFailure) { throw failure }
        catch (http: HttpException) { throw AiFailure(when (http.code()) {
            401, 403 -> "Kimi 密钥无效或无权限，请检查设置"
            429 -> "Kimi 限流或额度不足，请稍后重试"
            in 500..599 -> "Kimi 服务暂不可用，请稍后重试"
            else -> "Kimi 请求失败，请检查模型配置后重试"
        }) }
        catch (_: java.io.IOException) { throw AiFailure("网络连接失败，请检查网络后重试") }
        catch (_: Exception) { throw AiFailure("AI 响应格式无效，请重试") }
    }
}
class ConfiguredAiProvider @Inject constructor(private val store: AiConfigStore, private val mock: MockAiProvider, private val transport: KimiTransport) : AiProvider {
    override suspend fun reply(request: CoachRequest): String {
        val config = store.config.value
        return if (config.isMock) mock.reply(request) else KimiProvider(config, transport).reply(request)
    }
}
