package com.regionx.app.core.llm

import com.regionx.app.core.model.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okio.BufferedSource
import java.util.Base64

internal val LlmJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

internal fun LlmImage.dataUrl(): String =
    "data:$mimeType;base64," + Base64.getEncoder().encodeToString(data)

/** 按路径取字符串（数组下标用数字字符串），任一层缺失返回 null。 */
internal fun JsonObject.stringAt(vararg path: String): String? {
    var current: Any? = this
    for (key in path) {
        current = descend(current, key) ?: return null
    }
    return (current as? JsonPrimitive)?.takeIf { it.isString }?.content
}

internal fun JsonObject.nodeAt(vararg path: String): Any? {
    var current: Any? = this
    for (key in path) {
        current = descend(current, key) ?: return null
    }
    return current
}

private fun descend(node: Any?, key: String): Any? = when (node) {
    is JsonObject -> node[key]
    is JsonArray -> node.getOrNull(key.toIntOrNull() ?: return null)
    else -> null
}

/**
 * 三个适配器的公共部分：发请求、读 SSE、分派增量。
 * 子类只负责拼 payload 与解析单条流事件。
 */
abstract class ChatModelClientBase(
    protected val config: ModelConfig,
    protected val http: OkHttpClient,
) : ChatModelClient {

    /** 流事件的解析结果。 */
    protected sealed interface Chunk {
        /** 追加文本（空串由调用方忽略），可在输出本段后结束流。 */
        data class Text(val value: String, val done: Boolean = false) : Chunk

        /** 流正常结束。 */
        data object Done : Chunk

        /** 协议错误（接口在流里返回 error）。 */
        data class Fail(val message: String) : Chunk
    }

    protected abstract fun endpointUrl(): String
    protected abstract fun buildPayload(request: LlmChatRequest): String
    protected abstract fun parseChunk(data: String): Chunk

    final override fun stream(request: LlmChatRequest): Flow<String> = flow {
        val httpRequest = LlmHttp.createPostRequest(config, endpointUrl(), buildPayload(request))
        LlmHttp.withResponse(http.newCall(httpRequest)) { response ->
            val source: BufferedSource = LlmHttp.ensureSuccess(response)
            LlmHttp.readSseData(source) { data ->
                when {
                    data.isEmpty() -> true
                    data == "[DONE]" -> false
                    else -> when (val chunk = parseChunk(data)) {
                        is Chunk.Text -> {
                            if (chunk.value.isNotEmpty()) emit(chunk.value)
                            !chunk.done
                        }
                        is Chunk.Done -> false
                        is Chunk.Fail -> throw LlmException(LlmErrorKind.Protocol, chunk.message)
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)
}
