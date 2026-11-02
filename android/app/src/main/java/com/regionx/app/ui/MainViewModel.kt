package com.regionx.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.regionx.app.AppContainer
import com.regionx.app.core.model.ChatRole
import com.regionx.app.core.model.ModelConfig
import com.regionx.app.core.model.SessionRecord
import com.regionx.app.core.model.TaskRecord
import com.regionx.app.core.model.TaskStatus
import com.regionx.app.core.model.TaskConversation
import com.regionx.app.core.text.TitleGenerator
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 会话列表的一行。 */
data class SessionUi(
    val session: SessionRecord,
    val taskCount: Int,
    val hasActiveTask: Boolean = false,
) {
    /** 会话名是时间字符串时转成「今天 22:30」；用户改过名则原样显示。 */
    val displayName: String get() = formatSessionName(session.name, session.createdAt)
}

/** 任务卡片（队列与历史列表共用）。 */
data class TaskUi(
    val record: TaskRecord,
    val streamingText: String = "",
    val answerText: String = "",
) {
    val id: String get() = record.id
    val title: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(record.createdAt))
    val kindText: String get() = record.kind.displayName
    val statusText: String get() = record.status.displayName
    val createdAtText: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(record.createdAt))

    val isActive: Boolean get() = record.status.isActive
    val canCancel: Boolean get() = record.status.isActive
    val canRetry: Boolean get() = record.status == TaskStatus.Failed || record.status == TaskStatus.Cancelled
    val hasError: Boolean get() = !record.error.isNullOrBlank()

    /** 完成/取消/失败后才显示首行回答与展开箭头。 */
    val hasResultLine: Boolean
        get() = record.status == TaskStatus.Completed ||
            record.status == TaskStatus.Cancelled ||
            record.status == TaskStatus.Failed

    /**
     * 卡片那行小号字：处理中显示流式输出（末尾带光标），
     * 完成后显示 AI 回复的第一行（不是 20 字简介）。
     */
    val resultLine: String
        get() {
            val firstAnswer = previewLine(record.firstLine)
            return when {
                record.status == TaskStatus.Running -> TitleGenerator.streamingLine(previewLine(streamingText))
                firstAnswer.isNotEmpty() -> firstAnswer
                record.status == TaskStatus.Queued -> "等待上一个任务完成"
                record.status == TaskStatus.Failed ->
                    previewLine(record.error.orEmpty()).ifBlank { "处理失败，可重试" }
                else -> previewLine(record.summary)
            }
        }

    private fun previewLine(text: String): String = text.lineSequence().firstOrNull().orEmpty().trim()
}

/** 一条对话消息（详情页与展开区）。 */
data class MessageUi(
    val id: String,
    val role: ChatRole,
    val content: String,
    val imagePath: String? = null,
) {
    val isUser: Boolean get() = role == ChatRole.User
}

/** 任务详情。 */
data class TaskDetailUi(
    val task: TaskRecord,
    val sessionName: String,
    val mainAnswer: String,
    val followUps: List<MessageUi>,
    val isStreaming: Boolean = false,
    val error: String? = null,
)

fun formatSessionName(name: String, createdAt: Long): String {
    val parsed = runCatching { java.time.LocalDateTime.parse(name.replace(' ', 'T')) }.getOrNull()
        ?: return name
    val today = java.time.LocalDate.now()
    val date = parsed.toLocalDate()
    val time = String.format(Locale.CHINA, "%02d:%02d", parsed.hour, parsed.minute)
    return when {
        date == today -> "今天 $time"
        date == today.minusDays(1) -> "昨天 $time"
        date.year == today.year -> "${date.monthValue}月${date.dayOfMonth}日 $time"
        else -> "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
    }
}

/**
 * 主界面状态：会话列表、当前展开的会话、任务详情。
 * 与悬浮窗共用 [AppContainer] 的数据流，两边实时同步。
 */
class MainViewModel(private val container: AppContainer) : ViewModel() {

    private val _sessions = MutableStateFlow<List<SessionUi>>(emptyList())
    val sessions: StateFlow<List<SessionUi>> = _sessions.asStateFlow()

    private val _expandedSessionId = MutableStateFlow<String?>(null)
    val expandedSessionId: StateFlow<String?> = _expandedSessionId.asStateFlow()

    private val _sessionTasks = MutableStateFlow<Map<String, List<TaskUi>>>(emptyMap())
    val sessionTasks: StateFlow<Map<String, List<TaskUi>>> = _sessionTasks.asStateFlow()

    private val _searchText = MutableStateFlow("")
    val searchText: StateFlow<String> = _searchText.asStateFlow()

    private val _detail = MutableStateFlow<TaskDetailUi?>(null)
    val detail: StateFlow<TaskDetailUi?> = _detail.asStateFlow()

    private val _activeModelName = MutableStateFlow("未配置模型")
    val activeModelName: StateFlow<String> = _activeModelName.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val streaming = mutableMapOf<String, String>()
    private var detailJob: Job? = null

    /** 追问的独立 Job：停止生成只取消它，不影响详情页本身。 */
    private var followUpJob: Job? = null
    private var selectedTaskId: String? = null
    private var detailVisible = true

    val visibleSessions: StateFlow<List<SessionUi>> = combine(sessions, sessionTasks, searchText) { rows, tasks, search ->
        val key = search.trim()
        if (key.isEmpty()) rows else rows.filter { row ->
            row.session.name.contains(key, true) || row.displayName.contains(key, true) ||
                tasks[row.session.id].orEmpty().any {
                    it.title.contains(key, true) || it.record.title.contains(key, true) || it.resultLine.contains(key, true)
                }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            container.taskUpdates.collect { record -> onTaskChanged(record) }
        }
        viewModelScope.launch {
            container.taskStreaming.collect { progress ->
                streaming[progress.taskId] = progress.accumulated
                applyStreaming(progress.taskId, progress.accumulated)
            }
        }
        viewModelScope.launch { reload() }
    }

    suspend fun reload() {
        container.initialize()
        val rows = container.sessions()
        val loadedTasks = rows.associate { row -> row.session.id to container.tasks(row.session.id).map { TaskUi(it, streaming[it.id].orEmpty()) } }
        _sessionTasks.value = loadedTasks
        _sessions.value = rows.map {
            SessionUi(
                session = it.session,
                taskCount = it.taskCount,
                hasActiveTask = loadedTasks[it.session.id].orEmpty().any { task -> task.isActive },
            )
        }
        refreshActiveModel()
    }

    private suspend fun refreshActiveModel() {
        _activeModelName.value = container.activeModel()?.displayName ?: "未配置模型"
    }

    fun updateSearch(text: String) {
        _searchText.value = text
    }

    /** 当前可见的会话（按搜索词过滤）。 */
    fun filteredSessions(): List<SessionUi> {
        val key = _searchText.value.trim()
        if (key.isEmpty()) return _sessions.value
        val tasks = _sessionTasks.value
        return _sessions.value.filter { row ->
            row.session.name.contains(key, ignoreCase = true) ||
                row.displayName.contains(key, ignoreCase = true) ||
                tasks[row.session.id].orEmpty().any {
                    it.title.contains(key, ignoreCase = true) ||
                        it.record.title.contains(key, ignoreCase = true) ||
                        it.resultLine.contains(key, ignoreCase = true)
                }
        }
    }

    fun toggleSession(sessionId: String) {
        val current = _expandedSessionId.value
        if (current == sessionId) {
            _expandedSessionId.value = null
            return
        }
        _expandedSessionId.value = sessionId
        viewModelScope.launch { loadSessionTasks(sessionId) }
    }

    suspend fun loadSessionTasks(sessionId: String) {
        val list = container.tasks(sessionId).map { TaskUi(it, streaming[it.id].orEmpty()) }
        _sessionTasks.value = _sessionTasks.value + (sessionId to list)
    }

    fun removeSession(sessionId: String) {
        viewModelScope.launch {
            val tasks = container.tasks(sessionId)
            val record = container.session(sessionId) ?: return@launch
            container.deleteSession(record.id, tasks)
            if (_expandedSessionId.value == sessionId) _expandedSessionId.value = null
            reload()
        }
    }

    fun renameSession(sessionId: String, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            container.renameSession(sessionId, name.trim())
            reload()
        }
    }

    fun deleteTask(task: TaskRecord) {
        viewModelScope.launch {
            container.deleteTask(task)
            reload()
        }
    }

    fun cancelTask(taskId: String) {
        container.taskQueue.cancel(taskId)
    }

    fun retryTask(taskId: String) {
        viewModelScope.launch {
            val retried = container.retryTask(taskId)
            if (retried == null) _statusMessage.value = "没有可用的模型配置，请先在设置中添加模型。"
            else reload()
        }
    }

    // --------------------------------------------------------------- 详情

    fun openDetail(taskId: String) {
        selectedTaskId = taskId
        val previousFollowUp = followUpJob
        previousFollowUp?.cancel()
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            previousFollowUp?.join()
            val task = container.task(taskId) ?: return@launch
            val session = container.session(task.sessionId)
            val messages = container.messages(task.id)
            val conversation = TaskConversation.from(taskId, messages)
            val mainAnswer = if (task.status.isActive) streaming[taskId].orEmpty() else conversation.answer?.content ?: task.firstLine
            if (selectedTaskId != taskId) return@launch
            _detail.value = TaskDetailUi(
                task = task,
                sessionName = session?.let { formatSessionName(it.name, it.createdAt) } ?: "",
                mainAnswer = mainAnswer,
                // 首条用户消息与首条助手回复是「我的要求」和「AI 回复」，其余才是追问轮次。
                // 不能简单 drop(2)：失败/取消的任务可能没有助手回复。
                followUps = conversation.followUps
                    .map { MessageUi(it.id, it.role, it.content) },
                error = task.error,
            )
            if (detailVisible) container.taskReads.markRead(task)
        }
    }

    /** Activity 暂停时保留详情内容，但只有实际可见的结果才计为已读。 */
    fun setDetailVisible(visible: Boolean) {
        detailVisible = visible
        if (visible) _detail.value?.task?.let(container.taskReads::markRead)
    }

    fun closeDetail() {
        selectedTaskId = null
        followUpJob?.cancel()
        detailJob?.cancel()
        _detail.value = null
    }

    /**
     * 详情页追问：把流式文本写进最后一条助手消息。
     * 累计文本由 [detail] 状态驱动界面刷新，因此不需要额外的回调。
     */
    fun followUp(text: String) {
        val detail = _detail.value ?: return
        if (text.isBlank() || detail.isStreaming) return

        val userMessage = MessageUi(java.util.UUID.randomUUID().toString(), ChatRole.User, text)
        val assistantId = java.util.UUID.randomUUID().toString()
        _detail.value = detail.copy(
            followUps = detail.followUps + userMessage + MessageUi(assistantId, ChatRole.Assistant, ""),
            isStreaming = true,
            error = null,
        )
        followUpJob?.cancel()
        followUpJob = viewModelScope.launch {
            val owner = currentCoroutineContext()[Job]
            try {
                container.followUp(detail.task, text) { accumulated ->
                    val current = _detail.value
                    if (current?.task?.id == detail.task.id) {
                        _detail.value = current.copy(
                            followUps = current.followUps.map {
                                if (it.id == assistantId) it.copy(content = accumulated) else it
                            }
                        )
                    }
                }
            } catch (ex: kotlinx.coroutines.CancellationException) {
                markInterrupted(assistantId, "（已停止）")
            } catch (ex: Exception) {
                markInterrupted(assistantId, "（生成中断：${ex.message}）")
            } finally {
                val current = _detail.value
                if (current?.task?.id == detail.task.id && current.followUps.any { it.id == assistantId }) {
                    _detail.value = current.copy(isStreaming = false)
                }
                if (followUpJob === owner) followUpJob = null
            }
        }
    }

    private fun markInterrupted(assistantId: String, note: String) {
        val current = _detail.value ?: return
        _detail.value = current.copy(
            followUps = current.followUps.map {
                if (it.id == assistantId) {
                    it.copy(content = if (it.content.isEmpty()) note else it.content + "\n\n$note")
                } else {
                    it
                }
            }
        )
    }

    /** 停止生成：只取消追问请求，保留已收到的内容（对应需求 6.6）。 */
    fun cancelFollowUp() {
        followUpJob?.cancel()
    }

    fun retryFromDetail() {
        val detail = _detail.value ?: return
        viewModelScope.launch {
            val retried = container.retryTask(detail.task.id)
            if (retried != null) openDetail(retried.id)
            else _detail.value = detail.copy(error = "没有可用的模型配置，请先在设置中添加模型。")
        }
    }

    // ------------------------------------------------------------- 实时同步

    private fun onTaskChanged(record: TaskRecord) {
        val map = _sessionTasks.value.toMutableMap()
        var touched = false
        for ((sessionId, list) in map) {
            val index = list.indexOfFirst { it.id == record.id }
            if (index >= 0) {
                map[sessionId] = list.toMutableList().also {
                    it[index] = it[index].copy(record = record, streamingText = it[index].streamingText)
                }
                touched = true
            }
        }
        if (touched) _sessionTasks.value = map

        // 新任务（悬浮窗提交后主界面需要出现）
        viewModelScope.launch {
            val expanded = _expandedSessionId.value
            if (expanded != null && record.sessionId == expanded &&
                map[expanded]?.none { it.id == record.id } == true
            ) {
                loadSessionTasks(expanded)
            }
            reload()
            val current = _detail.value
            if (current?.task?.id == record.id) {
                val latest = container.task(record.id) ?: return@launch
                val conversation = TaskConversation.from(record.id, container.messages(record.id))
                val latestDetail = _detail.value
                if (latestDetail?.task?.id == record.id) {
                    _detail.value = latestDetail.copy(
                        task = latest,
                        error = latest.error,
                        mainAnswer = if (latest.status.isActive) streaming[record.id].orEmpty() else conversation.answer?.content ?: latest.firstLine,
                        followUps = if (latestDetail.isStreaming) latestDetail.followUps else conversation.followUps.map { MessageUi(it.id, it.role, it.content) },
                    )
                    if (detailVisible) container.taskReads.markRead(latest)
                }
            }
        }

        val detail = _detail.value
        if (detail != null && detail.task.id == record.id && record.status.isActive) {
            _detail.value = detail.copy(task = record, error = record.error)
        }
        if (record.status == TaskStatus.Completed) streaming.remove(record.id)
    }

    private fun applyStreaming(taskId: String, accumulated: String) {
        val current = _detail.value
        if (current?.task?.id == taskId && current.task.status.isActive) {
            _detail.value = current.copy(mainAnswer = accumulated)
        }
        val map = _sessionTasks.value.toMutableMap()
        for ((sessionId, list) in map) {
            val index = list.indexOfFirst { it.id == taskId }
            if (index >= 0) {
                map[sessionId] = list.toMutableList().also {
                    it[index] = it[index].copy(streamingText = accumulated)
                }
                _sessionTasks.value = map
                break
            }
        }
    }

    fun models(): List<ModelConfig> = emptyList()

    override fun onCleared() {
        followUpJob?.cancel()
        detailJob?.cancel()
        super.onCleared()
    }
}
