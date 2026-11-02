package com.regionx.app.core.text

import com.regionx.app.core.model.TaskKind

/**
 * 从 AI 结果本地生成任务标题（10 字以内）与简介（20 字以内），不额外调用模型。
 * 逻辑与 Windows 端 TitleGenerator 保持一致，保证两端标题一致。
 */
object TitleGenerator {
    const val TitleMaxLength = 10
    const val SummaryMaxLength = 20

    private const val StreamingCaret = " ▍"

    data class Result(val title: String, val summary: String)

    fun generate(kind: TaskKind, content: String?): Result {
        val clean = clean(content)
        if (clean.isBlank()) return Result(kind.displayName, fallbackSummary(kind))

        // 三类操作的格式都是「第一行 = 答案 / 译文 / 提取正文」
        val firstLine = firstLine(content)
        val title = take(firstLine, TitleMaxLength)

        // 简介只取第一行，避免把解释部分混进来；第一行太短时补上后续内容
        var summary = take(firstLine, SummaryMaxLength)
        if (summary.length < 6) summary = take(clean.replace('\n', ' '), SummaryMaxLength)
        if (summary.isEmpty()) summary = fallbackSummary(kind)
        return Result(title, summary)
    }

    /** 回答正文的第一行（去掉 Markdown 标记和空行）。 */
    fun firstLine(content: String?): String {
        val clean = clean(content)
        if (clean.isEmpty()) return ""
        for (line in clean.split('\n')) {
            val text = stripMarkdown(line)
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    /** 去掉行首尾常见的 Markdown 修饰（列表符、标题符、加粗、行内代码）。 */
    private fun stripMarkdown(line: String): String {
        var text = line.trim()
        if (text.isEmpty()) return text

        text = text.trimStart('#', '-', '*', '>', ' ')
        if (text.length > 2 && text[0].isDigit() && (text[1] == '.' || text[1] == '、')) {
            text = text.substring(2).trimStart()
        }

        text = text.replace("**", "").replace("__", "").replace("`", "")
        return text.trim()
    }

    private fun fallbackSummary(kind: TaskKind): String = when (kind) {
        TaskKind.Answer -> "已识别题目并作答"
        TaskKind.Translate -> "已翻译框内文字"
        TaskKind.Extract -> "已提取框内文字"
    }

    fun clean(content: String?): String {
        if (content.isNullOrBlank()) return ""
        val builder = StringBuilder(content.length)
        val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
        for (line in normalized.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                if (builder.isNotEmpty() && builder.last() != '\n') builder.append('\n')
                continue
            }
            builder.append(trimmed).append('\n')
        }
        return builder.toString().trim()
    }

    /** 按字符（码点）截断，避免把 emoji 或生僻字截成半个。 */
    fun take(text: String, max: Int): String {
        if (text.isEmpty()) return ""
        var count = 0
        var index = 0
        while (index < text.length && count < max) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            count++
        }
        return text.substring(0, index)
    }

    /** 处理中的流式一行：末尾带光标；还没收到内容时给出提示。 */
    fun streamingLine(accumulated: String?): String {
        val value = accumulated.orEmpty()
        return if (value.isNotEmpty()) value + StreamingCaret else "正在识别…"
    }
}
