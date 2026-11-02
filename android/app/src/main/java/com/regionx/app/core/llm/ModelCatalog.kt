package com.regionx.app.core.llm

import com.regionx.app.core.model.LlmProvider
import com.regionx.app.core.model.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class UpstreamModel(val id: String, val displayName: String? = null) {
    val label: String get() = displayName?.takeIf { it.isNotBlank() && it != id } ?: id
}

/**
 * 读取上游模型列表：
 * OpenAI 兼容 → GET {baseUrl}/models；Claude → GET {baseUrl}/v1/models；Gemini → GET {baseUrl}/v1beta/models。
 * baseUrl 允许只填主机名（如 https://api.example.com），会按接口类型自动补路径。
 * 与 Windows 端 ModelCatalog 对齐（8.10：多候选地址，只有 404 / 405 才换下一个）。
 */
class ModelCatalog(private val provider: HttpClientProvider) {

    suspend fun listModels(config: ModelConfig): List<UpstreamModel> = withContext(Dispatchers.IO) {
        withTimeout(30_000) { loadModels(config) }
    }

    private suspend fun loadModels(config: ModelConfig): List<UpstreamModel> {
        val urls = buildUrls(config)
        var last: LlmException? = null

        for (url in urls) {
            val builder = okhttp3.Request.Builder().url(url).get()
                .header("Accept", "application/json")
            LlmHttp.applyAuth(builder, config)
            LlmHttp.applyExtraHeaders(builder, config)

            val models = LlmHttp.withResponse(provider.client.newCall(builder.build())) { httpResponse ->
                val body = httpResponse.body?.string().orEmpty()
                if (httpResponse.isSuccessful) {
                    val node = runCatching { LlmJson.parseToJsonElement(body) }.getOrNull()
                        ?: throw LlmException(LlmErrorKind.Protocol, "模型列表返回的不是合法 JSON。")
                    val parsed = parse(config.provider, node)
                    val unique = LinkedHashMap<String, UpstreamModel>()
                    for (model in parsed) unique.putIfAbsent(model.id, model)
                    val comparator = compareBy<UpstreamModel, String>(String.CASE_INSENSITIVE_ORDER) { it.id }
                        .thenBy { it.id }
                    return@withResponse unique.values.sortedWith(comparator)
                }

                val failure = LlmHttp.mapStatus(httpResponse.code, body)
                last = failure
                if (httpResponse.code != 404 && httpResponse.code != 405) throw failure
                null
            }
            if (models != null) return models
        }

        throw last ?: LlmException(LlmErrorKind.Unknown, "无法访问模型列表接口。")
    }

    private fun parse(provider: LlmProvider, node: Any): List<UpstreamModel> = when (provider) {
        LlmProvider.Claude -> parseClaude(node)
        LlmProvider.Gemini -> parseGemini(node)
        LlmProvider.OpenAiCompatible -> parseOpenAi(node)
    }

    /** 候选模型列表地址（按优先级排序，去重）。 */
    private fun buildUrls(config: ModelConfig): List<String> {
        var raw = (config.baseUrl.ifBlank { fallback(config.provider) }).trim().trimEnd('/')

        // 用户可能直接粘贴了完整端点，先把这些后缀剥掉
        for (suffix in listOf("/chat/completions", "/completions", "/messages", "/models")) {
            if (raw.endsWith(suffix, ignoreCase = true)) {
                raw = raw.dropLast(suffix.length).trimEnd('/')
                break
            }
        }

        val urls = mutableListOf<String>()
        when (config.provider) {
            LlmProvider.Claude -> {
                urls += if (raw.endsWith("/v1", true)) "$raw/models" else "$raw/v1/models"
                urls += "$raw/models"
            }
            LlmProvider.Gemini -> {
                val root = if (raw.endsWith("/v1beta", true) || raw.endsWith("/v1", true)) raw else "$raw/v1beta"
                urls += "$root/models"
                urls += "$raw/models"
            }
            LlmProvider.OpenAiCompatible -> {
                // 常见的 OpenAI 兼容网关：/v1/models；也有 /models 与 /openai/v1/models
                urls += if (raw.endsWith("/v1", true)) "$raw/models" else "$raw/v1/models"
                urls += "$raw/models"
                urls += "$raw/openai/v1/models"
            }
        }
        return urls.distinctBy { it.lowercase() }
    }

    private fun fallback(provider: LlmProvider): String = when (provider) {
        LlmProvider.Claude -> ModelConfig.DefaultClaudeBaseUrl
        LlmProvider.Gemini -> ModelConfig.DefaultGeminiBaseUrl
        LlmProvider.OpenAiCompatible -> ModelConfig.DefaultOpenAiBaseUrl
    }

    /** OpenAI 兼容：{ data: [ { id } ] }，部分网关直接用数组。 */
    private fun parseOpenAi(node: Any): List<UpstreamModel> {
        val items = (node as? JsonArray) ?: (node as? JsonObject)?.get("data") as? JsonArray ?: return emptyList()
        return items.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj.stringAt("id") ?: obj.stringAt("name") ?: return@mapNotNull null
            if (id.isBlank()) null else UpstreamModel(id.trim())
        }
    }

    /** Claude：{ data: [ { id, display_name } ] }。 */
    private fun parseClaude(node: Any): List<UpstreamModel> {
        val items = (node as? JsonObject)?.get("data") as? JsonArray ?: return emptyList()
        return items.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj.stringAt("id") ?: return@mapNotNull null
            if (id.isBlank()) null else UpstreamModel(id.trim(), obj.stringAt("display_name"))
        }
    }

    /** Gemini：{ models: [ { name: "models/x", displayName, supportedGenerationMethods } ] }。 */
    private fun parseGemini(node: Any): List<UpstreamModel> {
        val items = (node as? JsonObject)?.get("models") as? JsonArray ?: return emptyList()
        return items.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val name = obj.stringAt("name") ?: return@mapNotNull null
            if (name.isBlank()) return@mapNotNull null

            // 只保留支持内容生成的模型（嵌入模型不能用于对话）
            val methods = obj["supportedGenerationMethods"] as? JsonArray
            if (methods != null && methods.none { (it as? JsonPrimitive)?.content == "generateContent" }) {
                return@mapNotNull null
            }

            val id = if (name.startsWith("models/", true)) name.substring("models/".length) else name
            UpstreamModel(id, obj.stringAt("displayName"))
        }
    }
}
