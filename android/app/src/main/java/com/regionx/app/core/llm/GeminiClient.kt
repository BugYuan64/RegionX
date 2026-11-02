package com.regionx.app.core.llm

import com.regionx.app.core.model.ModelConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.net.URLEncoder
import java.util.Base64

/**
 * Gemini API：POST {baseUrl}/v1beta/models/{model}:streamGenerateContent?alt=sse。
 * 流式解析 candidates[0].content.parts[*].text。
 */
class GeminiClient(
    config: ModelConfig,
    http: OkHttpClient,
) : ChatModelClientBase(config, http) {

    override fun endpointUrl(): String {
        val baseUrl = LlmHttp.normalizeBase(config.baseUrl, ModelConfig.DefaultGeminiBaseUrl)
        // 允许用户填 .../v1beta 或完整根地址
        val apiRoot = if (baseUrl.endsWith("/v1beta", true) || baseUrl.endsWith("/v1", true)) {
            baseUrl
        } else {
            "$baseUrl/v1beta"
        }

        var model = config.modelName.trim()
        if (model.startsWith("models/", ignoreCase = true)) model = model.substring("models/".length)
        val encoded = URLEncoder.encode(model, Charsets.UTF_8.name())
        return "$apiRoot/models/$encoded:streamGenerateContent?alt=sse"
    }

    override fun buildPayload(request: LlmChatRequest): String {
        val contents = buildJsonArray {
            request.messages.forEach { add(buildContent(it)) }
        }

        val payload = buildJsonObject {
            put("contents", contents)
            if (!request.systemPrompt.isNullOrBlank()) {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", request.systemPrompt) }) })
                })
            }

            val generation = buildJsonObject {
                config.temperature?.let { put("temperature", it) }
                config.maxTokens?.let { put("maxOutputTokens", it) }
            }
            if (generation.isNotEmpty()) put("generationConfig", generation)
        }
        return payload.toString()
    }

    override fun parseChunk(data: String): Chunk {
        val node = LlmJson.parseToJsonElement(data) as? JsonObject ?: return Chunk.Text("")
        if (node.containsKey("error") && node["error"] != null) {
            return Chunk.Fail("接口返回错误：${LlmHttp.truncate(node["error"].toString(), 400)}")
        }

        val parts = node.nodeAt("candidates", "0", "content", "parts") as? JsonArray
            ?: return Chunk.Text("")
        val builder = StringBuilder()
        for (part in parts) {
            val text = (part as? JsonObject)?.stringAt("text")
            if (!text.isNullOrEmpty()) builder.append(text)
        }
        return Chunk.Text(builder.toString())
    }

    private fun buildContent(message: LlmMessage): JsonObject {
        // Gemini 使用 "model" 表示助手角色
        val role = if (message.role == com.regionx.app.core.model.ChatRole.Assistant) "model" else "user"
        val parts = buildJsonArray {
            when (message) {
                is LlmMessage.User -> {
                    if (message.text.isNotBlank()) add(buildJsonObject { put("text", message.text) })
                    message.images.forEach { image ->
                        add(buildJsonObject {
                            put("inline_data", buildJsonObject {
                                put("mime_type", image.mimeType)
                                put("data", Base64.getEncoder().encodeToString(image.data))
                            })
                        })
                    }
                }
                is LlmMessage.Assistant ->
                    if (message.text.isNotBlank()) add(buildJsonObject { put("text", message.text) })
            }
        }
        return buildJsonObject {
            put("role", role)
            put("parts", parts)
        }
    }
}
