package com.regionx.app.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 操作类型；数值与 Windows 端 tasks.kind 一致，便于以后跨端导入。 */
enum class TaskKind {
    Answer, Translate, Extract;

    val displayName: String
        get() = when (this) {
            Answer -> "回答"
            Translate -> "翻译"
            Extract -> "提取图文"
        }
}

/** 任务状态；数值与 Windows 端 tasks.status 一致。 */
enum class TaskStatus {
    Queued, Running, Completed, Failed, Cancelled;

    val displayName: String
        get() = when (this) {
            Queued -> "排队中"
            Running -> "处理中"
            Completed -> "已完成"
            Failed -> "失败"
            Cancelled -> "已取消"
        }

    val isActive: Boolean get() = this == Queued || this == Running
}

/** 接口类型；数值与 Windows 端 model_configs.provider 一致。 */
enum class LlmProvider {
    OpenAiCompatible, Claude, Gemini;

    val displayName: String
        get() = when (this) {
            OpenAiCompatible -> "OpenAI 兼容"
            Claude -> "Claude"
            Gemini -> "Gemini"
        }
}

enum class ChatRole { User, Assistant }

/**
 * 一条模型配置。字段名与 Windows 端 `data/models.json` 一致（PascalCase），
 * 便于将来两端之间复制配置文件。
 */
@Serializable
data class ModelConfig(
    @SerialName("Id") val id: String = newId(),
    @SerialName("Name") val name: String = "",
    @SerialName("Provider") val provider: LlmProvider = LlmProvider.OpenAiCompatible,
    @SerialName("BaseUrl") val baseUrl: String = "",
    /** 明文 Key；只在内存中，落盘时由 [com.regionx.app.data.SecretStore] 加密。 */
    @SerialName("ApiKey") val apiKey: String = "",
    @SerialName("ModelName") val modelName: String = "",
    @SerialName("SupportsImages") val supportsImages: Boolean = true,
    @SerialName("Temperature") val temperature: Double? = null,
    @SerialName("MaxTokens") val maxTokens: Int? = null,
    /** 附加请求头，每行 "Name: Value"。 */
    @SerialName("ExtraHeaders") val extraHeaders: String? = null,
) {
    /** 工具栏「模型」按钮显示的文字：优先模型名，没有时退回配置名。 */
    val displayName: String get() = modelName.ifBlank { name }

    companion object {
        const val DefaultOpenAiBaseUrl = "https://api.openai.com/v1"
        const val DefaultClaudeBaseUrl = "https://api.anthropic.com"
        const val DefaultGeminiBaseUrl = "https://generativelanguage.googleapis.com"
    }
}

fun newId(): String = java.util.UUID.randomUUID().toString().replace("-", "")

@Serializable
data class SessionRecord(
    @SerialName("Id") val id: String = newId(),
    @SerialName("Name") val name: String = "",
    @SerialName("CreatedAt") val createdAt: Long = System.currentTimeMillis(),
    @SerialName("UpdatedAt") val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class TaskRecord(
    @SerialName("Id") val id: String = newId(),
    @SerialName("SessionId") val sessionId: String = "",
    @SerialName("Kind") val kind: TaskKind = TaskKind.Answer,
    /** 标题（10 字以内）。 */
    @SerialName("Title") val title: String = "",
    /** 简介（20 字以内），回答结束后生成，不含追问。 */
    @SerialName("Summary") val summary: String = "",
    /** 回答正文第一行，用于识别框队列里的一行展示。 */
    @SerialName("FirstLine") val firstLine: String = "",
    /** 截图文件名（相对于图片目录），不存绝对路径，便于导出。 */
    @SerialName("ImagePath") val imagePath: String? = null,
    @SerialName("RegionX") val regionX: Int = 0,
    @SerialName("RegionY") val regionY: Int = 0,
    @SerialName("RegionWidth") val regionWidth: Int = 0,
    @SerialName("RegionHeight") val regionHeight: Int = 0,
    @SerialName("Status") val status: TaskStatus = TaskStatus.Queued,
    @SerialName("ModelId") val modelId: String = "",
    @SerialName("ModelName") val modelName: String = "",
    @SerialName("Error") val error: String? = null,
    @SerialName("CreatedAt") val createdAt: Long = System.currentTimeMillis(),
    @SerialName("UpdatedAt") val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class MessageRecord(
    @SerialName("Id") val id: String = newId(),
    @SerialName("TaskId") val taskId: String = "",
    @SerialName("Role") val role: ChatRole = ChatRole.User,
    @SerialName("Content") val content: String = "",
    @SerialName("ImagePath") val imagePath: String? = null,
    @SerialName("CreatedAt") val createdAt: Long = System.currentTimeMillis(),
)
