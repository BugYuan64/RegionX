package com.regionx.app.core.llm

import com.regionx.app.core.model.ChatRole
import com.regionx.app.core.model.LlmProvider
import com.regionx.app.core.model.ModelConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

data class LlmImage(val data: ByteArray, val mimeType: String = "image/png")

sealed interface LlmMessage {
    val role: ChatRole

    data class User(val text: String, val images: List<LlmImage> = emptyList()) : LlmMessage {
        override val role: ChatRole get() = ChatRole.User
    }

    data class Assistant(val text: String) : LlmMessage {
        override val role: ChatRole get() = ChatRole.Assistant
    }
}

data class LlmChatRequest(
    val model: ModelConfig,
    val messages: List<LlmMessage>,
    val systemPrompt: String? = null,
)

enum class LlmErrorKind {
    Unknown, Network, Timeout, Authentication, RateLimited, Server, BadRequest, Protocol, Cancelled
}

class LlmException(
    val kind: LlmErrorKind,
    override val message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    val isRetryable: Boolean
        get() = kind == LlmErrorKind.Network || kind == LlmErrorKind.Timeout ||
            kind == LlmErrorKind.RateLimited || kind == LlmErrorKind.Server
}

/** 统一的流式对话接口。上层只依赖它，不关心具体厂商。 */
interface ChatModelClient {
    /** 逐块返回模型输出的文字。取消时抛出 CancellationException。 */
    fun stream(request: LlmChatRequest): Flow<String>
}

fun interface ChatModelClientFactory {
    fun create(config: ModelConfig): ChatModelClient
}

/**
 * 全局共享的 HTTP 客户端。代理地址可随时调整：代理变化时重建客户端
 * （正在进行的请求继续使用旧客户端，直到自然结束）。
 */
class HttpClientProvider(proxyUrl: String? = null) {
    @Volatile
    private var current: OkHttpClient = build(proxyUrl)

    @Volatile
    private var proxy: String? = proxyUrl

    val client: OkHttpClient get() = current

    fun setProxy(newProxyUrl: String?) {
        val normalized = newProxyUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (normalized == proxy) return
        proxy = normalized
        current = build(normalized)
    }

    private fun build(proxyUrl: String?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            // 与设置允许的最长业务超时一致，未配置业务超时的调用也不能永久挂起。
            .readTimeout(600, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(600, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        if (!proxyUrl.isNullOrBlank()) {
            val url = runCatching { java.net.URI(proxyUrl) }.getOrNull()
            val host = url?.host
            if (!host.isNullOrBlank()) {
                val port = url.port.takeIf { it > 0 } ?: 8080
                builder.proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(host, port)))
            }
        }
        return builder.build()
    }
}

internal val JsonMediaType = "application/json; charset=utf-8".toMediaType()

/** HTTP 层公共逻辑：状态码到错误类型的映射、SSE 行读取。与 Windows 端 LlmHttp 对齐。 */
object LlmHttp {
    /** 保持取消连接直到响应体读取结束；仅等待响应头的取消绑定不足以中断 SSE。 */
    internal suspend fun <T> withResponse(call: Call, block: suspend (Response) -> T): T {
        val context = currentCoroutineContext()
        // 无协程主体的子 Job 会在父任务取消时立即完成，不等待阻塞读取返回。
        val cancellation = Job(context[Job])
        val registration = cancellation.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }
        try {
            context.ensureActive()
            val result = call.execute().use { response -> block(response) }
            context.ensureActive()
            return result
        } catch (ex: IOException) {
            // Call.cancel() 导致的 IOException 不能覆盖业务 TimeoutCancellationException。
            context.ensureActive()
            throw wrapIoException(ex)
        } finally {
            registration.dispose()
            cancellation.complete()
        }
    }

    fun mapStatus(code: Int, body: String): LlmException {
        val detail = truncate(body, 600)
        return when {
            code == 401 || code == 403 ->
                LlmException(LlmErrorKind.Authentication, "认证失败（$code），请检查 API Key。$detail", code)
            code == 429 ->
                LlmException(LlmErrorKind.RateLimited, "请求过于频繁或额度不足（429）。$detail", 429)
            code == 400 || code == 404 || code == 422 ->
                LlmException(LlmErrorKind.BadRequest, "请求被拒绝（$code），请检查模型名与参数。$detail", code)
            code >= 500 ->
                LlmException(LlmErrorKind.Server, "服务端错误（$code）。$detail", code)
            else -> LlmException(LlmErrorKind.Unknown, "请求失败（$code）。$detail", code)
        }
    }

    fun wrapIoException(ex: IOException): LlmException = when (ex) {
        is InterruptedIOException -> LlmException(LlmErrorKind.Timeout, "网络请求超时。", cause = ex)
        else -> LlmException(LlmErrorKind.Network, "网络错误：${ex.message ?: ex.javaClass.simpleName}", cause = ex)
    }

    /** 状态码非 2xx 时抛异常；成功时返回响应体流。 */
    fun ensureSuccess(response: Response): okio.BufferedSource {
        if (response.isSuccessful) {
            return response.body!!.source()
        }
        val body = response.body?.string().orEmpty()
        throw mapStatus(response.code, body)
    }

    /** 按行读取 SSE，返回 "data: ..." 后的内容；忽略注释与空行。返回 false 表示调用方要求结束。 */
    suspend fun readSseData(
        source: okio.BufferedSource,
        onData: suspend (String) -> Boolean,
    ) {
        while (true) {
            val line = source.readUtf8Line() ?: return
            if (line.isEmpty() || line[0] == ':') continue
            if (line.startsWith("data:")) {
                val data = line.substring(5).trim()
                if (!onData(data)) return
            }
        }
    }

    fun truncate(text: String?, max: Int): String {
        if (text.isNullOrEmpty()) return ""
        val trimmed = text.trim()
        return if (trimmed.length <= max) trimmed else trimmed.take(max) + "…"
    }

    /** 构造带认证头与附加头的 POST 请求。 */
    fun createPostRequest(
        config: ModelConfig,
        url: String,
        payload: String,
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JsonMediaType))
            .header("Accept", "text/event-stream")
        applyAuth(builder, config)
        applyExtraHeaders(builder, config)
        return builder.build()
    }

    fun applyAuth(builder: Request.Builder, config: ModelConfig) {
        val key = config.apiKey.trim()
        when (config.provider) {
            LlmProvider.Claude -> {
                if (key.isNotEmpty()) builder.header("x-api-key", key)
                builder.header("anthropic-version", "2023-06-01")
            }
            LlmProvider.Gemini -> if (key.isNotEmpty()) builder.header("x-goog-api-key", key)
            LlmProvider.OpenAiCompatible -> if (key.isNotEmpty()) builder.header("Authorization", "Bearer $key")
        }
    }

    fun applyExtraHeaders(builder: Request.Builder, config: ModelConfig) {
        val raw = config.extraHeaders ?: return
        for (line in raw.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val idx = trimmed.indexOf(':')
            if (idx <= 0) continue
            val name = trimmed.substring(0, idx).trim()
            val value = trimmed.substring(idx + 1).trim()
            if (name.isEmpty()) continue
            // 认证头由适配器负责，避免被覆盖
            if (name.equals("Authorization", true) ||
                name.equals("x-api-key", true) ||
                name.equals("x-goog-api-key", true) ||
                name.equals("Content-Type", true)
            ) {
                continue
            }
            builder.header(name, value)
        }
    }

    fun normalizeBase(baseUrl: String, fallback: String): String {
        val value = baseUrl.ifBlank { fallback }.trim()
        return value.trimEnd('/')
    }
}
