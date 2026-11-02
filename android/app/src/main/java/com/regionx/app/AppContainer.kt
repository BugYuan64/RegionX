package com.regionx.app

import android.content.Context
import android.util.Log
import com.regionx.app.core.llm.ChatModelClientFactory
import com.regionx.app.core.llm.DefaultChatModelClientFactory
import com.regionx.app.core.llm.HttpClientProvider
import com.regionx.app.core.llm.LlmChatRequest
import com.regionx.app.core.llm.ModelCatalog
import com.regionx.app.core.model.AppSettings
import com.regionx.app.core.model.ChatRole
import com.regionx.app.core.model.MessageRecord
import com.regionx.app.core.model.ModelConfig
import com.regionx.app.core.model.SessionRecord
import com.regionx.app.core.model.TaskKind
import com.regionx.app.core.model.TaskRecord
import com.regionx.app.core.model.TaskStatus
import com.regionx.app.core.model.TaskConversation
import com.regionx.app.core.model.newId
import com.regionx.app.core.model.systemPromptFor
import com.regionx.app.core.tasks.TaskImageSource
import com.regionx.app.core.tasks.TaskPersistence
import com.regionx.app.core.tasks.TaskProgress
import com.regionx.app.core.tasks.TaskQueue
import com.regionx.app.core.tasks.TaskRunner
import com.regionx.app.data.ModelConfigRepository
import com.regionx.app.data.RegionXDatabase
import com.regionx.app.data.SecretStore
import com.regionx.app.data.SettingsRepository
import com.regionx.app.data.TaskReadStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * 应用级单例容器：数据库、模型配置、设置、任务队列。
 * 主窗口与悬浮窗服务共用同一份状态，保证任务与流式输出处处同步。
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, ex -> logError(ex) })

    val database = RegionXDatabase(appContext)
    val taskReads = TaskReadStore(appContext)
    private val secrets = SecretStore(appContext)
    val settingsRepository = SettingsRepository(database)
    val modelRepository = ModelConfigRepository(database, secrets)

    val http = HttpClientProvider()
    val clientFactory: ChatModelClientFactory = DefaultChatModelClientFactory(http)
    val catalog = ModelCatalog(http)

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val settingsMutex = Mutex()
    private val initializeMutex = Mutex()
    private var initialized = false

    /** 任务状态变化（running / completed / failed / cancelled）。 */
    private val _taskUpdates = MutableSharedFlow<TaskRecord>(extraBufferCapacity = 64)
    val taskUpdates: SharedFlow<TaskRecord> = _taskUpdates.asSharedFlow()

    /** 流式文本（已节流）。 */
    private val _taskStreaming = MutableSharedFlow<TaskProgress>(extraBufferCapacity = 64)
    val taskStreaming: SharedFlow<TaskProgress> = _taskStreaming.asSharedFlow()

    val taskQueue: TaskQueue

    private val imageStore = ImageStore(database)

    init {
        val persistence = object : TaskPersistence {
            override suspend fun updateTask(task: TaskRecord) {
                database.updateTask(task)
                _taskUpdates.emit(task)
            }

            override suspend fun saveAssistantMessage(task: TaskRecord, content: String) {
                database.savePrimaryAnswer(task, content)
            }
        }

        val runner = TaskRunner(
            factory = clientFactory,
            persistence = persistence,
            images = imageStore,
            settings = { _settings.value },
            onProgress = { progress -> _taskStreaming.tryEmit(progress) },
            onChanged = { task -> _taskUpdates.tryEmit(task) },
        )

        taskQueue = TaskQueue(
            scope = appScope,
            runner = runner,
            maxConcurrency = _settings.value.maxConcurrency,
            onChanged = { task -> _taskUpdates.tryEmit(task) },
        )
    }

    /** 启动时加载设置与模型，并把上次异常退出的任务标为已取消。 */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        initializeMutex.withLock {
            if (initialized) return@withLock
            val loaded = settingsRepository.load()
            _settings.value = loaded
            http.setProxy(loaded.proxyUrl)
            taskQueue.updateConcurrency(loaded.maxConcurrency)
            database.recoverInterruptedTasks()
            // 每进程只恢复一次，Activity 重建不能取消当前仍在运行的任务。
            modelRepository.all()
            initialized = true
        }
    }

    suspend fun updateSettings(mutate: (AppSettings) -> AppSettings) {
        initialize()
        settingsMutex.withLock {
            val next = mutate(_settings.value).normalized()
            settingsRepository.save(next)
            _settings.value = next
            http.setProxy(next.proxyUrl)
            taskQueue.updateConcurrency(next.maxConcurrency)
        }
    }

    suspend fun activeModel(): ModelConfig? {
        initialize()
        val settings = _settings.value
        val id = settings.activeModelId
        if (!id.isNullOrEmpty()) {
            modelRepository.get(id)?.let { config ->
                return config.copy(modelName = settings.activeUpstreamModelName?.takeIf { it.isNotBlank() } ?: config.modelName)
            }
        }
        return modelRepository.all().firstOrNull()
    }

    /** 配置 ID 唯一决定启用上游；不同上游之间不复用目录模型名。 */
    suspend fun selectUpstream(id: String) {
        initialize()
        require(modelRepository.get(id) != null) { "上游配置已删除。" }
        updateSettings {
            if (it.activeModelId == id) it else it.copy(activeModelId = id, activeUpstreamModelName = null)
        }
    }

    suspend fun selectUpstreamModel(name: String, upstreamId: String? = null) {
        val config = activeModel() ?: throw IllegalStateException("没有可用的上游配置。")
        require(name.isNotBlank()) { "模型名不能为空。" }
        require(upstreamId == null || upstreamId == config.id) { "启用的上游已变化，请重新选择模型。" }
        updateSettings {
            require(it.activeModelId == null || it.activeModelId == config.id) { "启用的上游已变化，请重新选择模型。" }
            it.copy(activeModelId = config.id, activeUpstreamModelName = name.trim())
        }
    }

    // ------------------------------------------------------------- 会话与任务

    suspend fun createSession(): SessionRecord {
        val now = System.currentTimeMillis()
        val session = SessionRecord(
            id = newId(),
            name = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(now)),
            createdAt = now,
            updatedAt = now,
        )
        database.insertSession(session)
        return session
    }

    suspend fun sessions() = database.sessions()

    suspend fun session(id: String) = database.session(id)

    suspend fun tasks(sessionId: String) = database.tasks(sessionId)

    suspend fun task(id: String) = database.task(id)

    suspend fun messages(taskId: String) = database.messages(taskId)

    suspend fun renameSession(id: String, name: String) = database.renameSession(id, name)

    suspend fun deleteSession(id: String, tasks: List<TaskRecord>) {
        tasks.forEach {
            taskQueue.cancelAndJoin(it.id)
            imageStore.delete(it.imagePath)
        }
        database.deleteSession(id)
    }

    suspend fun deleteTask(task: TaskRecord) {
        taskQueue.cancelAndJoin(task.id)
        database.deleteTask(task.id)
        imageStore.delete(task.imagePath)
    }

    /** 核心流程：图片已截好 → 建记录 → 写首条消息 → 入队（不等待模型）。 */
    suspend fun enqueueTask(
        session: SessionRecord,
        kind: TaskKind,
        png: ByteArray,
        region: RegionBounds,
        model: ModelConfig,
    ): TaskRecord? {
        val imagePath = imageStore.save(png) ?: return null
        val now = System.currentTimeMillis()
        val task = TaskRecord(
            id = newId(),
            sessionId = session.id,
            kind = kind,
            title = kind.displayName,
            summary = "已提交，等待处理",
            imagePath = imagePath,
            regionX = region.x,
            regionY = region.y,
            regionWidth = region.width,
            regionHeight = region.height,
            status = TaskStatus.Queued,
            modelId = model.id,
            modelName = model.modelName,
            createdAt = now,
            updatedAt = now,
        )
        database.insertTask(task)
        database.insertMessage(
            MessageRecord(
                taskId = task.id,
                role = ChatRole.User,
                content = when (kind) {
                    TaskKind.Answer -> "回答框内内容"
                    TaskKind.Translate -> "翻译框内文字（目标：${_settings.value.translateTargetLanguage}）"
                    TaskKind.Extract -> "提取框内文字"
                },
                imagePath = imagePath,
                createdAt = now,
            )
        )
        database.touchSession(session.id, now)
        taskQueue.enqueue(task, model)
        _taskUpdates.tryEmit(task)
        return task
    }

    suspend fun retryTask(taskId: String): TaskRecord? {
        taskQueue.cancelAndJoin(taskId)
        val task = database.task(taskId) ?: return null
        val model = modelForTask(task) ?: return null
        val next = task.copy(
            status = TaskStatus.Queued,
            error = null,
            summary = "已重新提交",
            firstLine = "",
            modelId = model.id,
            modelName = model.modelName,
            updatedAt = System.currentTimeMillis(),
        )
        database.updateTask(next)
        taskQueue.enqueue(next, model)
        _taskUpdates.tryEmit(next)
        return next
    }

    /**
     * 追问：追加用户消息，流式回调累计文本，最后写入助手消息。
     * 与 Windows 端 WorkspaceService.FollowUpAsync 行为一致：只在本次追问上附带截图。
     */
    suspend fun followUp(
        task: TaskRecord,
        userText: String,
        onDelta: (String) -> Unit,
    ): String {
        val settings = _settings.value
        val model = modelForTask(task)
            ?: throw IllegalStateException("没有可用的模型配置。")

        val history = TaskConversation.from(task.id, database.messages(task.id)).orderedMessages
        val userMessage = MessageRecord(
            taskId = task.id,
            role = ChatRole.User,
            content = userText,
            imagePath = if (settings.includeImageOnFollowUp) task.imagePath else null,
        )
        database.insertMessage(userMessage)

        val imageBytes = if (settings.includeImageOnFollowUp) imageStore.read(task.imagePath) else null

        val messages = mutableListOf<com.regionx.app.core.llm.LlmMessage>()
        for (message in history + userMessage) {
            if (message.id == userMessage.id) {
                val text = "${settings.followUpPrompt}\n\n$userText"
                messages += com.regionx.app.core.llm.LlmMessage.User(
                    text,
                    if (imageBytes != null) listOf(com.regionx.app.core.llm.LlmImage(imageBytes)) else emptyList(),
                )
            } else if (message.role == ChatRole.Assistant) {
                messages += com.regionx.app.core.llm.LlmMessage.Assistant(message.content)
            } else {
                messages += com.regionx.app.core.llm.LlmMessage.User(message.content)
            }
        }

        val request = LlmChatRequest(model, messages, systemPromptFor(task.kind, settings))
        val client = clientFactory.create(model)
        val builder = StringBuilder()
        var lastReport = 0L

        try {
            withTimeout(settings.timeoutSeconds * 1000L) {
                client.stream(request)
                    .flowOn(Dispatchers.IO)
                    .collect { delta ->
                        builder.append(delta)
                        val now = System.currentTimeMillis()
                        if (lastReport == 0L || now - lastReport >= 70) {
                            lastReport = now
                            onDelta(builder.toString())
                        }
                    }
            }
        } catch (ex: Exception) {
            val note = when (ex) {
                is TimeoutCancellationException -> "（请求超时，已保留部分内容）"
                is CancellationException -> "（已停止）"
                else -> "（生成中断：${ex.message}）"
            }
            withContext(NonCancellable) {
                database.insertMessage(MessageRecord(taskId = task.id, role = ChatRole.Assistant,
                    content = if (builder.isEmpty()) note else "$builder\n\n$note"))
                database.touchSession(task.sessionId)
            }
            throw ex
        }
        onDelta(builder.toString())

        val answer = builder.toString()
        withContext(NonCancellable) {
            database.insertMessage(
                MessageRecord(taskId = task.id, role = ChatRole.Assistant, content = answer)
            )
            database.touchSession(task.sessionId)
        }
        return answer
    }

    /** 识别框当前所属会话（服务重建 / 主界面「继续」时使用）。 */
    suspend fun overlaySession(): SessionRecord? {
        val id = _settings.value.overlaySessionId ?: return null
        return session(id)
    }

    suspend fun setOverlaySession(id: String?) {
        updateSettings { it.copy(overlaySessionId = id) }
    }

    private suspend fun modelForTask(task: TaskRecord): ModelConfig? {
        val config = modelRepository.get(task.modelId) ?: return activeModel()
        // 旧版 ModelName 存配置显示名；新任务保存提交时的实际模型，重试/追问保持一致。
        val name = task.modelName.takeIf { it.isNotBlank() && it != config.name } ?: config.modelName
        return config.copy(modelName = name)
    }

    fun resolveImage(path: String?): File? = imageStore.resolve(path)

    fun logError(throwable: Throwable) {
        runCatching {
            val file = File(database.logsDirectory(), "error.log")
            val text = "${java.util.Date()} ${throwable.javaClass.simpleName}: ${throwable.message}\n" +
                throwable.stackTraceToString() + "\n"
            file.appendText(text)
        }
        Log.e("RegionX", "error", throwable)
    }
}

data class RegionBounds(val x: Int, val y: Int, val width: Int, val height: Int)

/** 截图文件管理：数据库只保存相对文件名，便于整体迁移。 */
class ImageStore(private val database: RegionXDatabase) : TaskImageSource {

    fun save(png: ByteArray): String? = runCatching {
        val dir = database.imagesDirectory()
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val name = "$stamp-${newId().take(8)}.png"
        File(dir, name).writeBytes(png)
        name
    }.getOrNull()

    fun resolve(relativePath: String?): File? {
        val file = database.imageFile(relativePath) ?: return null
        return if (file.exists()) file else null
    }

    fun read(relativePath: String?): ByteArray? =
        resolve(relativePath)?.let { runCatching { it.readBytes() }.getOrNull() }

    fun delete(relativePath: String?) {
        resolve(relativePath)?.let { runCatching { it.delete() } }
    }

    override suspend fun load(task: TaskRecord): ByteArray? = read(task.imagePath)
}
