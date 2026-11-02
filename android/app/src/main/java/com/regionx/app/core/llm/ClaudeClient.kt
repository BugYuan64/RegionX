package com.regionx.app.core.llm

import com.regionx.app.core.model.ModelConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.util.Base64

/**
 * Claude Messages API：POST {baseUrl}/v1/messages。
 * 流式解析 content_block_delta 事件的 delta.text。必须携带 max_tokens。
 */
class ClaudeClient(
    config: ModelConfig,
    http: OkHttpClient,
) : ChatModelClientBase(config, http) {

    override fun endpointUrl(): String {
        val baseUrl = LlmHttp.normalizeBase(config.baseUrl, ModelConfig.DefaultClaudeBaseUrl)
        return if (baseUrl.endsWith("/v1", ignoreCase = true)) "$baseUrl/messages" else "$baseUrl/v1/messages"
    }

    override fun buildPayload(request: LlmChatRequest): String {
        val messages = buildJsonArray {
            request.messages.forEach { add(buildMessage(it)) }
        }

        val payload = buildJsonObject {
            put("model", config.modelName)
            put("messages", messages)
            put("max_tokens", config.maxTokens ?: 4096)
            put("stream", true)
            if (!request.systemPrompt.isNullOrBlank()) put("system", request.systemPrompt)
            config.temperature?.let { put("temperature", it) }
        }
        return payload.toString()
    }

    override fun parseChunk(data: String): Chunk {
        val node = LlmJson.parseToJsonElement(data) as? JsonObject ?: return Chunk.Text("")
        return when (node.stringAt("type")) {
            "content_block_delta" -> {
                // 文本增量；部分兼容网关把增量放在 delta.content 里
                val text = node.stringAt("delta", "text").orEmpty()
                    .ifEmpty { node.stringAt("delta", "content").orEmpty() }
                Chunk.Text(text)
            }
            "message_delta" -> {
                if (node.stringAt("delta", "stop_reason") != null) Chunk.Done else Chunk.Text("")
            }
            "message_stop" -> Chunk.Done
            "error" -> Chunk.Fail("接口返回错误：${LlmHttp.truncate(data, 400)}")
            else ->
                if (node.containsKey("error") && node["error"] != null) {
                    Chunk.Fail("接口返回错误：${LlmHttp.truncate(data, 400)}")
                } else {
                    Chunk.Text("")
                }
        }
    }

    private fun buildMessage(message: LlmMessage): JsonObject = when (message) {
        is LlmMessage.Assistant -> buildJsonObject {
            put("role", "assistant")
            put("content", message.text)
        }
        is LlmMessage.User -> if (message.images.isEmpty()) {
            buildJsonObject {
                put("role", "user")
                put("content", message.text)
            }
        } else {
            val parts = buildJsonArray {
                message.images.forEach { image ->
                    add(buildJsonObject {
                        put("type", "image")
                        put("source", buildJsonObject {
                            put("type", "base64")
                            put("media_type", image.mimeType)
                            put("data", Base64.getEncoder().encodeToString(image.data))
                        })
                    })
                }
                if (message.text.isNotBlank()) {
                    add(buildJsonObject { put("type", "text"); put("text", message.text) })
                }
            }
            buildJsonObject {
                put("role", "user")
                put("content", parts)
            }
        }
    }
}
