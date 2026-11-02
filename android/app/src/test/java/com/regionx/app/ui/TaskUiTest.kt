package com.regionx.app.ui

import com.regionx.app.core.model.TaskRecord
import com.regionx.app.core.model.TaskStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

class TaskUiTest {
    private lateinit var originalZone: TimeZone

    @Before fun setTimeZone() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
    }

    @After fun restoreTimeZone() {
        TimeZone.setDefault(originalZone)
    }

    @Test fun titleUsesTheCreationDateRatherThanTheGeneratedTitleOrUpdatedTime() {
        val ui = TaskUi(TaskRecord(
            createdAt = 0L,
            updatedAt = 86_400_000L,
            title = "Generated title",
        ))

        assertEquals("1970-01-01 08:00:00", ui.title)
    }

    @Test fun completedPreviewUsesOnlyTheFirstAnswerLine() {
        val ui = TaskUi(TaskRecord(
            status = TaskStatus.Completed,
            firstLine = "  First answer  \r\nExplanation stays hidden",
            summary = "Summary must not replace the answer",
        ))

        assertEquals("First answer", ui.resultLine)
    }

    @Test fun runningPreviewUsesOnlyFirstLineAndKeepsItsStreamingIndicator() {
        val ui = TaskUi(TaskRecord(status = TaskStatus.Running), streamingText = "First line\nNext line")

        assertEquals("First line ▍", ui.resultLine)
    }

    @Test fun summaryAndErrorFallbacksRemainSingleLine() {
        assertEquals("Summary", TaskUi(TaskRecord(
            status = TaskStatus.Completed, summary = " Summary \nSecond line",
        )).resultLine)
        assertEquals("Connection failed", TaskUi(TaskRecord(
            status = TaskStatus.Failed, error = " Connection failed \rDiagnostics",
        )).resultLine)
    }

    @Test fun activeAndFailurePlaceholdersRemainAvailableWithoutAnAnswer() {
        assertEquals("等待上一个任务完成", TaskUi(TaskRecord(status = TaskStatus.Queued)).resultLine)
        assertEquals("正在识别…", TaskUi(TaskRecord(status = TaskStatus.Running)).resultLine)
        assertEquals("处理失败，可重试", TaskUi(TaskRecord(status = TaskStatus.Failed)).resultLine)
    }
}
