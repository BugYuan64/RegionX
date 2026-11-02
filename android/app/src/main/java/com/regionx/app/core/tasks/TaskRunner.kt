package com.regionx.app.core.tasks

import com.regionx.app.core.llm.ChatModelClientFactory
import com.regionx.app.core.llm.LlmChatRequest
import com.regionx.app.core.llm.LlmErrorKind
import com.regionx.app.core.llm.LlmException
import com.regionx.app.core.llm.LlmImage
import com.regionx.app.core.llm.LlmMessage
import com.regionx.app.core.model.AppSettings
import com.regionx.app.core.model.ModelConfig
import com.regionx.app.core.model.TaskKind
import com.regionx.app.core.model.TaskRecord
import com.regionx.app.core.model.TaskStatus
import com.regionx.app.core.model.systemPromptFor
import com.regionx.app.core.text.TitleGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

data class TaskProgress(val taskId: String, val accumulated: String)

/** 任务持久化与本机图片读取。由应用层实现，核心层不依赖 Android 类型。 */
interface TaskPersistence {
    suspend fun updateTask(task: TaskRecord)
    suspend fun saveAssistantMessage(task: TaskRecord, content: String)
}

fun interface TaskImageSource {
    suspend fun load(task: TaskRecord): ByteArray?
}

/**
 * 单个任务的执行器：调用 LLM 网关、节流推送流式文本、写回数据库。
 * 与 Windows 端 TaskRunner 等价：任务之间互不影响，由 TaskQueue 控制并发。
 */
class TaskRunner(
    private val factory: ChatModelClientFactory,
    private val persistence: TaskPersistence,
    private val images: TaskImageSource,
    private val settings: () -> AppSettings,
    private val onProgress: (TaskProgress) -> Unit,
    private val onChanged: (TaskRecord) -> Unit,
) {
    /** 50 到 100ms 合并一次，避免流式刷新过于频繁。 */
    var progressThrottleMs: Long = 70

    suspend fun run(task: TaskRecord, model: ModelConfig) {
        val current = settings()
        val content = StringBuilder()
        var lastReport = 0L

        var state = task.copy(status = TaskStatus.Running, error = null, modelId = model.id, modelName = model.modelName)
        state = state.copy(updatedAt = System.currentTimeMillis())
        try {
            persistence.updateTask(state)
            onChanged(state)
            val messages = buildInitialMessages(state, model, current)
            val request = LlmChatRequest(model, messages, systemPromptFor(state.kind, current))
            val client = factory.create(model)

            withTimeout(current.timeoutSeconds * 1000L) {
                client.stream(request)
                    .onEach { delta ->
                        content.append(delta)
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= progressThrottleMs) {
                            lastReport = now
                            onProgress(TaskProgress(state.id, content.toString()))
                        }
                    }
                    .catch { ex ->
                        if (ex is CancellationException) throw ex
                        throw TaskAbort(ex)
                    }
                    .flowOn(Dispatchers.IO)
                    .collect { /* 文本在 onEach 中累积 */ }
            }

            val finalText = content.toString()
            if (finalText.isEmpty()) {
                throw LlmException(LlmErrorKind.Protocol, "模型没有返回任何内容。")
            }

            // 收尾补发一次完整文本，保证界面与最终结果一致
            onProgress(TaskProgress(state.id, finalText))

            val (title, summary) = TitleGenerator.generate(state.kind, finalText)
            state = state.copy(
                title = title,
                summary = summary,
                firstLine = TitleGenerator.firstLine(finalText),
                status = TaskStatus.Completed,
                updatedAt = System.currentTimeMillis(),
            )
            persistence.saveAssistantMessage(state, finalText)
            persistence.updateTask(state)
            onChanged(state)
        } catch (ex: CancellationException) {
            // 用户取消与超时都会抛 CancellationException：
            // - 超时：仍有已收到的内容，保留并标记「已取消」+ 原因；
            // - 用户取消：同样保留已生成内容。
            finishAsCancelled(
                state,
                content.toString(),
                if (isTimeout(ex)) "请求超时（${current.timeoutSeconds} 秒），已保留部分内容。" else null,
            )
        } catch (ex: TaskAbort) {
            val cause = ex.cause
            val delegated = if (cause is LlmException) cause
            else LlmException(LlmErrorKind.Unknown, "发生未知错误：${cause?.message ?: ex.message}")
            finishAsFailed(state, content.toString(), describe(delegated))
        } catch (ex: LlmException) {
            finishAsFailed(state, content.toString(), describe(ex))
        } catch (ex: Exception) {
            finishAsFailed(state, content.toString(), "发生未知错误：${ex.message}")
        }
    }

    private fun isTimeout(ex: Throwable): Boolean = ex is kotlinx.coroutines.TimeoutCancellationException

    /** 把流里的异常转成失败结果（避免在 catch 运算符里直接抛出原始异常）。 */
    private class TaskAbort(cause: Throwable?) : Exception(cause)

    /** 排队或尚未执行的任务也必须先落盘，再通知界面。 */
    suspend fun cancelQueued(task: TaskRecord) {
        finishAsCancelled(task, "", null)
    }

    private suspend fun finishAsCancelled(task: TaskRecord, partial: String, error: String?) = withContext(NonCancellable) {
        val hasContent = partial.isNotBlank()
        var next = task.copy(status = TaskStatus.Cancelled, error = error, updatedAt = System.currentTimeMillis())
        if (hasContent) {
            val (title, summary) = TitleGenerator.generate(task.kind, partial)
            next = next.copy(title = title, summary = summary, firstLine = TitleGenerator.firstLine(partial))
            persistence.saveAssistantMessage(next, partial)
        }
        persistence.updateTask(next)
        onChanged(next)
    }

    private suspend fun finishAsFailed(task: TaskRecord, partial: String, error: String) = withContext(NonCancellable) {
        var next = task.copy(status = TaskStatus.Failed, error = error, updatedAt = System.currentTimeMillis())
        if (partial.isNotBlank()) {
            // 流式中途断开：保留已收到的内容并标记未完成
            next = next.copy(
                summary = "生成中断，" + TitleGenerator.take(error, 14),
                firstLine = TitleGenerator.firstLine(partial),
            )
            persistence.saveAssistantMessage(next, partial)
        }
        persistence.updateTask(next)
        onChanged(next)
    }

    private suspend fun buildInitialMessages(
        task: TaskRecord,
        model: ModelConfig,
        settings: AppSettings,
    ): List<LlmMessage> {
        if (!model.supportsImages) {
            throw LlmException(LlmErrorKind.BadRequest, "当前模型不支持图片输入，请选择支持图片的模型后重试。")
        }
        val png = try {
            images.load(task)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            throw LlmException(LlmErrorKind.BadRequest, "读取截图失败，请重新截图后重试。", cause = ex)
        }
        if (png == null || png.isEmpty()) {
            throw LlmException(LlmErrorKind.BadRequest, "截图文件不存在或为空，请重新截图后重试。")
        }

        val prompt = when (task.kind) {
            TaskKind.Answer -> "请回答图片中的题目或问题。"
            TaskKind.Translate -> "请把图片中的文字翻译成${settings.translateTargetLanguage}。"
            TaskKind.Extract -> "请提取图片中的全部文字。"
        }
        return listOf(LlmMessage.User(prompt, listOf(LlmImage(png))))
    }

    private fun describe(ex: LlmException): String = when (ex.kind) {
        LlmErrorKind.Authentication -> ex.message
        LlmErrorKind.RateLimited -> "请求过于频繁或额度不足（429），请稍后重试。"
        LlmErrorKind.Server -> "模型服务暂时不可用（5xx），请稍后重试。"
        LlmErrorKind.Network -> ex.message
        LlmErrorKind.Timeout -> "网络请求超时，请检查网络或代理。"
        LlmErrorKind.BadRequest, LlmErrorKind.Protocol -> ex.message
        LlmErrorKind.Cancelled -> "已取消"
        LlmErrorKind.Unknown -> ex.message
    }
}
