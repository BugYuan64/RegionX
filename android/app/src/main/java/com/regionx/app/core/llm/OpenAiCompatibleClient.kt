package com.regionx.app.core.llm

import com.regionx.app.core.model.ModelConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/** OpenAI 兼容接口：{baseUrl}/chat/completions，流式解析 choices[0].delta.content。 */
class OpenAiCompatibleClient(
    config: ModelConfig,
    http: OkHttpClient,
) : ChatModelClientBase(config, http) {

    override fun endpointUrl(): String {
        val baseUrl = LlmHttp.normalizeBase(config.baseUrl, ModelConfig.DefaultOpenAiBaseUrl)
        return "$baseUrl/chat/completions"
    }

    override fun buildPayload(request: LlmChatRequest): String {
        val messages = buildJsonArray {
            if (!request.systemPrompt.isNullOrBlank()) {
                add(buildJsonObject { put("role", "system"); put("content", request.systemPrompt) })
            }
            request.messages.forEach { add(buildMessage(it)) }
        }

        val payload = buildJsonObject {
            put("model", config.modelName)
            put("messages", messages)
            put("stream", true)
            config.temperature?.let { put("temperature", it) }
            config.maxTokens?.let { put("max_tokens", it) }
        }
        return payload.toString()
    }

    override fun parseChunk(data: String): Chunk {
        val node = LlmJson.parseToJsonElement(data) as? JsonObject ?: return Chunk.Text("")
        if (node.containsKey("error") && node["error"] != null) {
            return Chunk.Fail("接口返回错误：${LlmHttp.truncate(node["error"].toString(), 400)}")
        }

        val text = node.stringAt("choices", "0", "delta", "content")
        val finish = node.stringAt("choices", "0", "finish_reason")
        val done = finish == "stop" || finish == "length" || finish == "content_filter"
        return Chunk.Text(text.orEmpty(), done = done)
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
            // 多模态：content 为数组，先文本再图片
            val parts = buildJsonArray {
                if (message.text.isNotBlank()) {
                    add(buildJsonObject { put("type", "text"); put("text", message.text) })
                }
                message.images.forEach { image ->
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject { put("url", image.dataUrl()) })
                    })
                }
            }
            buildJsonObject {
                put("role", "user")
                put("content", parts)
            }
        }
    }
}
