package com.regionx.app.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CaptureMode { FullScreen, Region }

/**
 * 应用设置。字段与 Windows 端 `data/settings.json` 对应（PascalCase），
 * 默认提示词保持一致，保证两端行为相同。
 */
@Serializable
data class AppSettings(
    @SerialName("AnswerPrompt")
    val answerPrompt: String =
        "请仔细查看图片，完成其中的题目或回答其中提出的问题。输出格式必须严格遵守：\n" +
            "第一行只写最终答案本身，简洁直接，不要加「答案：」「答：」之类的前缀，也不要换行；\n" +
            "从第二行开始写解释、推导过程或理由，可以分段。\n" +
            "使用简体中文回答。",

    @SerialName("TranslatePrompt")
    val translatePrompt: String =
        "请把图片中的全部文字翻译成目标语言。输出格式必须严格遵守：\n" +
            "第一行只写完整译文，不要加任何前缀；\n" +
            "另起一段，对重点短语或字词逐个换行解释，每行格式为「原词 —— 释义」，不要合并成一段。",

    @SerialName("TranslateTargetLanguage")
    val translateTargetLanguage: String = "简体中文",

    @SerialName("ExtractPrompt")
    val extractPrompt: String =
        "请提取图片中的全部文字，只输出文字本身，保持原有换行与顺序。" +
            "不要添加任何解释、前缀、标题，也不要用代码块包裹。",

    @SerialName("FollowUpPrompt")
    val followUpPrompt: String = "下面是对之前内容的追问，请结合图片和对话历史回答。",

    /** 追问时是否附带当时的截图。 */
    @SerialName("IncludeImageOnFollowUp")
    val includeImageOnFollowUp: Boolean = true,

    /** HTTP 代理地址，如 http://127.0.0.1:7890；为空表示直连。 */
    @SerialName("ProxyUrl")
    val proxyUrl: String? = null,

    /** 同时运行的任务数（2 到 3）。 */
    @SerialName("MaxConcurrency")
    val maxConcurrency: Int = 3,

    /** 请求超时（秒）。 */
    @SerialName("TimeoutSeconds")
    val timeoutSeconds: Int = 120,

    @SerialName("ActiveModelId")
    val activeModelId: String? = null,

    /** 当前上游临时选择的模型名；不修改该配置保存的默认模型。 */
    @SerialName("ActiveUpstreamModelName")
    val activeUpstreamModelName: String? = null,

    /** 默认截取全屏；区域模式保留可调整的识别框。 */
    @SerialName("CaptureMode")
    val captureMode: CaptureMode = CaptureMode.FullScreen,

    /** 两个独立悬浮球的位置（"x,y"，屏幕像素）。 */
    @SerialName("AnswerBubblePosition")
    val answerBubblePosition: String? = null,

    @SerialName("ExitBubblePosition")
    val exitBubblePosition: String? = null,

    @SerialName("QueueBubblePosition")
    val queueBubblePosition: String? = null,

    /** 识别框最后一次的位置与大小（"x,y,w,h"，屏幕像素）。 */
    @SerialName("OverlayBounds")
    val overlayBounds: String? = null,

    @SerialName("OverlayTopmost")
    val overlayTopmost: Boolean = true,

    /** 截取前延迟（毫秒），用于等待界面稳定。 */
    @SerialName("CaptureDelayMs")
    val captureDelayMs: Int = 120,

    /** 主题标识：dark / light / system。Android 端默认跟随系统。 */
    @SerialName("ThemeName")
    val themeName: String = "system",

    /** Android：识别框当前所属会话，服务被回收后用「继续」重建时不新开会话。 */
    @SerialName("OverlaySessionId")
    val overlaySessionId: String? = null,
) {
    fun normalized(): AppSettings = copy(
        maxConcurrency = maxConcurrency.coerceIn(1, 5),
        timeoutSeconds = if (timeoutSeconds < 10 || timeoutSeconds > 600) 120 else timeoutSeconds,
        captureDelayMs = captureDelayMs.coerceIn(0, 2000),
    )
}

/** 三类操作共用的系统提示词选择。 */
fun systemPromptFor(kind: TaskKind, settings: AppSettings): String = when (kind) {
    TaskKind.Answer -> settings.answerPrompt
    TaskKind.Translate -> "${settings.translatePrompt}\n目标语言：${settings.translateTargetLanguage}"
    TaskKind.Extract -> settings.extractPrompt
}
