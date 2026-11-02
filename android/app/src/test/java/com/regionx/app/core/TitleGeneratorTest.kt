package com.regionx.app.core

import com.regionx.app.core.model.TaskKind
import com.regionx.app.core.text.TitleGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 标题/简介生成与 Windows 端 TitleGenerator 的行为对齐（需求 6.2、6.3、7.9）。 */
class TitleGeneratorTest {

    @Test
    fun `first line becomes title and summary`() {
        val result = TitleGenerator.generate(
            TaskKind.Answer,
            "牛顿第二定律\n\n根据 F = ma，加速度与外力成正比。",
        )
        assertEquals("牛顿第二定律", result.title)
        assertEquals("牛顿第二定律", result.summary)
    }

    @Test
    fun `title is truncated to ten characters`() {
        val source = "这是一段非常非常长的答案首行内容"
        val result = TitleGenerator.generate(TaskKind.Answer, source)
        assertEquals(10, TitleGenerator.take(source, 10).length)
        assertEquals("这是一段非常非常长的", result.title)
    }

    @Test
    fun `markdown markers are stripped from the first line`() {
        val result = TitleGenerator.generate(TaskKind.Translate, "## **Hello world**\n\n解释……")
        assertEquals("Hello worl", result.title)
    }

    @Test
    fun `ordered list prefix is stripped`() {
        assertEquals("答案是 42", TitleGenerator.firstLine("1. 答案是 42\n说明"))
    }

    @Test
    fun `short first line falls back to longer text for summary`() {
        val result = TitleGenerator.generate(TaskKind.Extract, "是\n\n后续还有更多被提取的文字")
        assertEquals("是", result.title)
        assertTrue(result.summary.length > 1)
    }

    @Test
    fun `empty content falls back to kind display name`() {
        val result = TitleGenerator.generate(TaskKind.Translate, "   ")
        assertEquals("翻译", result.title)
        assertEquals("已翻译框内文字", result.summary)
    }

    @Test
    fun `take counts code points not utf16 units`() {
        // 表情占两个 UTF-16 单元，不能截成半个
        assertEquals("😀😀", TitleGenerator.take("😀😀😀😀", 2))
    }

    @Test
    fun `streaming line appends caret`() {
        assertEquals("部分输出 ▍", TitleGenerator.streamingLine("部分输出"))
        assertEquals("正在识别…", TitleGenerator.streamingLine(""))
    }
}
