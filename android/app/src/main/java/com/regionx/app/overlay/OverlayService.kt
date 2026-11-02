package com.regionx.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp
import com.regionx.app.AppContainer
import com.regionx.app.MainActivity
import com.regionx.app.R
import com.regionx.app.RegionBounds
import com.regionx.app.RegionXApp
import com.regionx.app.core.llm.UpstreamModel
import com.regionx.app.core.model.CaptureMode
import com.regionx.app.core.model.ModelConfig
import com.regionx.app.core.model.SessionRecord
import com.regionx.app.core.model.TaskKind
import com.regionx.app.platform.ScreenCapture
import com.regionx.app.ui.theme.RegionXTheme
import com.regionx.app.ui.MainViewModel
import com.regionx.app.ui.TaskUi
import com.regionx.app.ui.TaskDetailScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 区域框可选，问答/退出球常驻；任务和结果统一留在历史会话。 */
class OverlayService : LifecycleService() {
    private lateinit var container: AppContainer
    private lateinit var capture: ScreenCapture
    private lateinit var host: OverlayWindowHost
    private var frameEntry: OverlayWindowHost.Entry? = null
    private var answerEntry: OverlayWindowHost.Entry? = null
    private var exitEntry: OverlayWindowHost.Entry? = null
    private var pickerEntry: OverlayWindowHost.Entry? = null
    private var confirmationEntry: OverlayWindowHost.Entry? = null
    private var queueBubbleEntry: OverlayWindowHost.Entry? = null
    private var inboxEntry: OverlayWindowHost.Entry? = null
    private var conversationEntry: OverlayWindowHost.Entry? = null
    private val conversationStore = ViewModelStore()
    private lateinit var conversationModel: MainViewModel
    private var projection: MediaProjection? = null
    private var session: SessionRecord? = null
    private var windowsRequested = true
    private var observing = false
    private var catalogJob: Job? = null
    private var catalogGeneration = 0
    private var inboxGeneration = 0
    private var inboxRequested = false
    private var lastConfig: ModelConfig? = null
    private var geometry by mutableStateOf(OverlayGeometry(0, 0, 0, 0))
    private var answerPoint by mutableStateOf(FloatingPoint(0, 0))
    private var exitPoint = FloatingPoint(0, 0)
    private var queuePoint = FloatingPoint(0, 0)
    private var sessionTasks by mutableStateOf<List<TaskUi>>(emptyList())
    private var unreadIds by mutableStateOf<Set<String>>(emptySet())
    private var activeModel by mutableStateOf<ModelConfig?>(null)
    private var catalogModels by mutableStateOf<List<UpstreamModel>>(emptyList())
    private var catalogLoading by mutableStateOf(false)
    private var catalogError by mutableStateOf<String?>(null)
    private var menuOpen by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var statusToast: Toast? = null
    private var themeName by mutableStateOf("system")
    private var captureMode = CaptureMode.FullScreen
    private val screenWidth: Int get() = metrics().widthPixels
    private val screenHeight: Int get() = metrics().heightPixels

    override fun onCreate() {
        super.onCreate()
        container = (application as RegionXApp).container
        conversationModel = ViewModelProvider(object : ViewModelStoreOwner {
            override val viewModelStore = conversationStore
        }, (application as RegionXApp).viewModelFactory)[MainViewModel::class.java]
        host = OverlayWindowHost(this, this)
        capture = ScreenCapture(getSystemService(Context.WINDOW_SERVICE) as WindowManager) {
            OverlayController.markProjectionReady(false)
            toast("屏幕授权已结束，请返回主界面重新授权。")
        }
        OverlayController.markRunning(true)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.service_channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply { description = getString(R.string.service_channel_description) })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                startForegroundWithNotification()
                attachFromIntent(intent)
                lifecycleScope.launch { openSession() }
            }
            ACTION_RESTORE_PROJECTION -> {
                startForegroundWithNotification()
                attachFromIntent(intent)
                showWindows()
            }
            ACTION_HIDE -> hideWindows()
            ACTION_SHOW -> showWindows()
            ACTION_EXIT -> showExitConfirmation()
        }
        return START_NOT_STICKY
    }

    private fun attachFromIntent(intent: Intent) {
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else { @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA) }
        if (data == null) return
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val next = manager.getMediaProjection(code, data) ?: return
            runCatching { projection?.stop() }
            projection = next
            capture.attach(next)
            OverlayController.markProjectionReady(capture.isReady)
        } catch (ex: Exception) {
            OverlayController.markProjectionReady(false)
            container.logError(ex)
            toast("屏幕授权不可用，请返回主界面重新授权。")
        }
    }

    private suspend fun openSession() {
        try {
            hideWindows()
            windowsRequested = true
            container.initialize()
            val settings = container.settings.value
            geometry = OverlayGeometry.parse(settings.overlayBounds, screenWidth, screenHeight)
            answerPoint = parsePoint(settings.answerBubblePosition,
                FloatingPoint(screenWidth - dp(ANSWER_WIDTH), screenHeight / 2 - dp(64)))
            exitPoint = parsePoint(settings.exitBubblePosition,
                FloatingPoint(screenWidth - dp(BALL_SIZE), screenHeight / 2 + dp(64)))
            queuePoint = parsePoint(settings.queueBubblePosition,
                FloatingPoint(screenWidth - dp(BALL_SIZE), screenHeight / 2))
            session = container.createSession()
            sessionTasks = emptyList()
            unreadIds = emptySet()
            container.setOverlaySession(session!!.id)
            refreshSettings()
            refreshInbox()
            snapAllBalls()
            observe()
            reconcileWindows()
        } catch (ex: Exception) {
            container.logError(ex)
            toast("无法启动截图：${ex.message}")
            stopSelf()
        }
    }

    private fun observe() {
        if (observing) return
        observing = true
        lifecycleScope.launch {
            snapshotFlow { geometry }.distinctUntilChanged().collect { geo ->
                frameEntry?.let {
                    it.params.x = geo.left; it.params.y = geo.top
                    it.params.width = geo.width; it.params.height = geo.height
                    host.update(it)
                }
            }
        }
        lifecycleScope.launch {
            container.settings.collect {
                refreshSettings()
                reconcileWindows()
            }
        }
        lifecycleScope.launch {
            container.taskUpdates.collect { task ->
                if (task.sessionId == session?.id) refreshInbox()
            }
        }
        lifecycleScope.launch {
            container.taskStreaming.collect { progress ->
                sessionTasks = sessionTasks.map {
                    if (it.id == progress.taskId) it.copy(streamingText = progress.accumulated) else it
                }
            }
        }
        lifecycleScope.launch {
            container.taskReads.revision.collect { refreshUnread() }
        }
    }

    private suspend fun refreshInbox() {
        val current = session ?: return
        val streaming = sessionTasks.associate { it.id to it.streamingText }
        val tasks = container.tasks(current.id).sortedByDescending { it.createdAt }
            .map { TaskUi(it, streaming[it.id].orEmpty()) }
        if (session?.id != current.id) return
        sessionTasks = tasks
        refreshUnread()
    }

    private fun refreshUnread() {
        unreadIds = sessionTasks.filter { container.taskReads.isUnread(it.record) }.mapTo(mutableSetOf()) { it.id }
    }

    private suspend fun refreshSettings() {
        val settings = container.settings.value
        themeName = settings.themeName
        captureMode = settings.captureMode
        val next = container.activeModel()
        if (next != lastConfig) {
            lastConfig = next
            activeModel = next
            closeModelPicker()
            catalogModels = emptyList()
            catalogError = null
        }
    }

    private fun reconcileWindows() {
        if (session == null) return
        if (captureMode == CaptureMode.FullScreen) {
            frameEntry?.let(host::remove); frameEntry = null
        } else if (frameEntry == null) {
            // WindowManager 按添加顺序叠放：切回区域模式时始终先加框，再加两个球。
            closeModelPicker(); closeExitConfirmation()
            closeTaskQueue()
            answerEntry?.let(host::remove); answerEntry = null
            exitEntry?.let(host::remove); exitEntry = null
            queueBubbleEntry?.let(host::remove); queueBubbleEntry = null
            geometry = geometry.clampInto(screenWidth, screenHeight)
            val geo = geometry
            val entry = entry("region", geo.width, geo.height, geo.left, geo.top)
            frameEntry = entry
            host.add(entry) { RegionXTheme(themeName) {
                OverlayFrame(geometry, { geometry = it }, screenWidth, screenHeight)
            } }
        }
        if (answerEntry == null) {
            val entry = entry("answer", dp(ANSWER_WIDTH), dp(BALL_SIZE), answerPoint.x, answerPoint.y)
            answerEntry = entry
            host.add(entry) { RegionXTheme(themeName) {
                AnswerBubble(busy, menuOpen, ::captureAndQueue, ::toggleModelPicker,
                    { dx, dy -> moveBubble(Bubble.Answer, dx, dy) }, { finishDrag(Bubble.Answer) },
                    inwardOnLeft = answerPoint.x + dp(ANSWER_WIDTH) / 2 >= screenWidth / 2)
            } }
        }
        if (exitEntry == null) {
            val entry = entry("exit", dp(BALL_SIZE), dp(BALL_SIZE), exitPoint.x, exitPoint.y)
            exitEntry = entry
            host.add(entry) { RegionXTheme(themeName) {
                ExitBubble(::showExitConfirmation, { dx, dy -> moveBubble(Bubble.Exit, dx, dy) }, { finishDrag(Bubble.Exit) })
            } }
        }
        if (queueBubbleEntry == null) {
            val entry = entry("queueBubble", dp(BALL_SIZE), dp(BALL_SIZE), queuePoint.x, queuePoint.y)
            queueBubbleEntry = entry
            host.add(entry) { RegionXTheme(themeName) {
                QueueBubble(unreadIds.size, ::toggleTaskQueue,
                    { dx, dy -> moveBubble(Bubble.Queue, dx, dy) }, { finishDrag(Bubble.Queue) })
            } }
        }
        positionBalls()
        setChromeVisible(windowsRequested && !busy)
    }

    private fun entry(name: String, width: Int, height: Int, x: Int, y: Int) =
        OverlayWindowHost.Entry(this, name, OverlayWindowHost.baseParams(width, height, x, y))

    private enum class Bubble { Answer, Exit, Queue }

    private fun moveBubble(bubble: Bubble, dx: Float, dy: Float) {
        closeModelPicker()
        closeTaskQueue()
        val point = when (bubble) { Bubble.Answer -> answerPoint; Bubble.Exit -> exitPoint; Bubble.Queue -> queuePoint }
        val next = clampPoint(FloatingPoint(point.x + dx.roundToInt(), point.y + dy.roundToInt()),
            if (bubble == Bubble.Answer) ANSWER_WIDTH else BALL_SIZE)
        when (bubble) { Bubble.Answer -> answerPoint = next; Bubble.Exit -> exitPoint = next; Bubble.Queue -> queuePoint = next }
        positionBalls()
    }

    private fun finishDrag(bubble: Bubble) {
        when (bubble) {
            Bubble.Answer -> answerPoint = snapPoint(answerPoint, ANSWER_WIDTH)
            Bubble.Exit -> exitPoint = snapPoint(exitPoint, BALL_SIZE)
            Bubble.Queue -> queuePoint = snapPoint(queuePoint, BALL_SIZE)
        }
        positionBalls()
        savePositions()
    }

    private fun snapPoint(point: FloatingPoint, width: Int) = FloatingLayout.snapToEdge(
        point.x, point.y, dp(width), dp(BALL_SIZE), screenWidth, screenHeight, insets().first, insets().second)

    private fun snapAllBalls() {
        answerPoint = snapPoint(answerPoint, ANSWER_WIDTH)
        exitPoint = snapPoint(exitPoint, BALL_SIZE)
        queuePoint = snapPoint(queuePoint, BALL_SIZE)
    }

    private fun clampPoint(point: FloatingPoint, width: Int) = FloatingLayout.clampPoint(
        point.x, point.y, dp(width), dp(BALL_SIZE), screenWidth, screenHeight, insets().first, insets().second)

    private fun positionBalls() {
        answerPoint = clampPoint(answerPoint, ANSWER_WIDTH)
        exitPoint = clampPoint(exitPoint, BALL_SIZE)
        listOf(answerEntry to answerPoint, exitEntry to exitPoint, queueBubbleEntry to queuePoint).forEach { (entry, point) ->
            entry?.let { it.params.x = point.x; it.params.y = point.y; host.update(it) }
        }
    }

    private fun toggleModelPicker() {
        if (pickerEntry != null) { closeModelPicker(); return }
        if (busy || !windowsRequested) return
        if (activeModel == null) { toast("请先在设置中添加上游配置。"); return }
        closeTaskQueue(); closeConversation()
        val p = FloatingLayout.modelMenu(answerPoint.x, answerPoint.y, dp(ANSWER_WIDTH), dp(BALL_SIZE),
            dp(FloatingSizes.ModelWidth), dp(FloatingSizes.ModelHeight), screenWidth, screenHeight, dp(4), insets().first, insets().second)
        if (p.height <= 0) { toast("请先将问答悬浮球拖到空旷处。"); return }
        val entry = entry("models", p.width, p.height, p.x, p.y)
        pickerEntry = entry
        menuOpen = true
        host.add(entry) { RegionXTheme(themeName) {
            UpstreamModelPanel(catalogModels, activeModel?.modelName, catalogLoading, catalogError,
                ::selectModel, ::loadCatalog, ::closeModelPicker)
        } }
        loadCatalog()
    }

    private fun loadCatalog() {
        val config = activeModel ?: return
        val generation = ++catalogGeneration
        catalogJob?.cancel()
        catalogLoading = true
        catalogError = null
        catalogModels = listOfNotNull(config.modelName.takeIf(String::isNotBlank)?.let { UpstreamModel(it) })
        catalogJob = lifecycleScope.launch {
            try {
                val available = container.catalog.listModels(config)
                if (activeModel != config || generation != catalogGeneration) return@launch
                catalogModels = (available + catalogModels).distinctBy { it.id }
                if (available.isEmpty()) catalogError = "上游没有返回模型，保留当前模型。"
            } catch (ex: CancellationException) { throw ex }
            catch (ex: Exception) { if (generation == catalogGeneration) catalogError = "模型列表获取失败：${ex.message}" }
            finally { if (generation == catalogGeneration) catalogLoading = false }
        }
    }

    private fun selectModel(name: String) {
        if (catalogModels.none { it.id == name }) return
        val upstreamId = activeModel?.id ?: return
        lifecycleScope.launch {
            try {
                container.selectUpstreamModel(name, upstreamId)
                refreshSettings()
                closeModelPicker()
                toast("已切换模型：$name")
            } catch (ex: Exception) { toast("切换失败：${ex.message}") }
        }
    }

    private fun closeModelPicker() {
        catalogGeneration++
        catalogJob?.cancel(); catalogJob = null
        pickerEntry?.let(host::remove); pickerEntry = null
        menuOpen = false
    }

    private fun toggleTaskQueue() {
        if (inboxRequested) { dismissTaskQueue(); return }
        if (busy || !windowsRequested) return
        closeModelPicker(); closeExitConfirmation(); closeConversation()
        inboxRequested = true
        val generation = ++inboxGeneration
        lifecycleScope.launch {
            refreshInbox()
            if (!windowsRequested || busy || !inboxRequested || generation != inboxGeneration) return@launch
            val p = FloatingLayout.modelMenu(queuePoint.x, queuePoint.y, dp(BALL_SIZE), dp(BALL_SIZE),
                dp(FloatingSizes.QueueWidth), dp(FloatingSizes.QueueHeight), screenWidth, screenHeight,
                dp(4), insets().first, insets().second)
            if (p.height <= 0) { closeTaskQueue(); return@launch }
            val entry = entry("taskQueue", p.width, p.height, p.x, p.y)
            inboxEntry = entry
            host.add(entry) { RegionXTheme(themeName) {
                FloatingTaskQueue(sessionTasks, unreadIds, ::openConversation, ::dismissTaskQueue)
            } }
        }
    }

    private fun closeTaskQueue() {
        inboxRequested = false
        inboxGeneration++
        inboxEntry?.let(host::remove); inboxEntry = null
    }

    /** 只有用户主动关闭已展开的队列才批量已读；截图、隐藏和旋转不消费结果。 */
    private fun dismissTaskQueue() {
        if (inboxEntry != null) {
            sessionTasks.forEach { container.taskReads.markRead(it.record) }
            refreshUnread()
        }
        closeTaskQueue()
    }

    private fun openConversation(taskId: String) {
        if (sessionTasks.none { it.id == taskId }) return
        closeTaskQueue(); closeModelPicker(); closeExitConfirmation()
        conversationModel.openDetail(taskId)
        if (conversationEntry != null) return
        val entry = entry("conversation", 1, 1, 0, 0)
        entry.params.flags = entry.params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() and
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS.inv() or WindowManager.LayoutParams.FLAG_DIM_BEHIND
        entry.params.dimAmount = .18f
        entry.params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        conversationEntry = entry
        // 阅读/追问时收起区域框，旋转重建的框也不能遮挡对话或拦截输入。
        frameEntry?.view?.visibility = View.INVISIBLE
        positionConversation()
        host.add(entry) { RegionXTheme(themeName) {
            val detail by conversationModel.detail.collectAsStateWithLifecycle()
            val settings by container.settings.collectAsStateWithLifecycle()
            val current = detail
            if (current == null) {
                Text("正在载入对话…", fontSize = 12.sp)
            } else {
                TaskDetailScreen(current, container.resolveImage(current.task.imagePath), settings.includeImageOnFollowUp,
                    ::closeConversation, conversationModel::retryFromDetail,
                    conversationModel::followUp, conversationModel::cancelFollowUp,
                    { include -> lifecycleScope.launch { container.updateSettings { it.copy(includeImageOnFollowUp = include) } } },
                    floating = true)
            }
        } }
    }

    private fun positionConversation() {
        conversationEntry?.let { entry ->
            val (top, bottom) = insets()
            val width = dp(360).coerceAtMost((screenWidth - dp(16)).coerceAtLeast(1))
            val available = (screenHeight - top - bottom - dp(16)).coerceAtLeast(1)
            val height = dp(560).coerceAtMost(available)
            entry.params.x = (screenWidth - width) / 2
            entry.params.y = top + (available - height) / 2
            entry.params.width = width; entry.params.height = height
            if (entry.view != null) host.update(entry)
        }
    }

    private fun closeConversation() {
        conversationEntry?.let { entry ->
            val input = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            input.hideSoftInputFromWindow(entry.view?.windowToken, 0)
            host.remove(entry)
        }
        conversationEntry = null
        conversationModel.closeDetail()
        frameEntry?.view?.visibility = if (windowsRequested && !busy) View.VISIBLE else View.INVISIBLE
    }

    private fun captureAndQueue() {
        val current = session ?: return
        if (busy || confirmationEntry != null) return
        val model = activeModel ?: run { toast("请先在设置中添加上游配置。"); return }
        if (!capture.isReady) { toast("屏幕授权已失效，请返回主界面重新授权。"); return }
        busy = true
        lifecycleScope.launch {
            try {
                statusToast?.cancel(); statusToast = null
                closeModelPicker()
                closeTaskQueue(); closeConversation()
                setChromeVisible(false)
                val frameBoundary = System.nanoTime()
                delay(container.settings.value.captureDelayMs.toLong().coerceIn(80, 2000))
                val mode = captureMode
                val geo = geometry
                val (png, bounds) = withContext(Dispatchers.IO) {
                    if (mode == CaptureMode.FullScreen) {
                        val screen = capture.captureScreen(frameBoundary)
                        screen.png to RegionBounds(0, 0, screen.width, screen.height)
                    } else {
                        capture.captureRegion(geo.left, geo.top, geo.right, geo.bottom, frameBoundary) to
                            RegionBounds(geo.left, geo.top, geo.width, geo.height)
                    }
                }
                val task = container.enqueueTask(current, TaskKind.Answer, png,
                    bounds, model)
                refreshInbox()
                toast(if (task == null) "截图保存失败，请检查存储空间。" else "问答任务已创建，已加入队列")
            } catch (ex: CancellationException) { throw ex }
            catch (ex: Exception) { toast("截图失败：${ex.message}"); container.logError(ex) }
            finally { busy = false; setChromeVisible(windowsRequested) }
        }
    }

    private fun showExitConfirmation() {
        if (confirmationEntry != null || busy) return
        closeModelPicker()
        closeTaskQueue(); closeConversation()
        val width = dp(300).coerceAtMost(screenWidth)
        val height = dp(190).coerceAtMost(screenHeight)
        val entry = entry("confirmExit", width, height, (screenWidth - width) / 2, (screenHeight - height) / 2)
        confirmationEntry = entry
        host.add(entry) { RegionXTheme(themeName) { ExitConfirmation(::exit, ::closeExitConfirmation) } }
    }
    private fun closeExitConfirmation() { confirmationEntry?.let(host::remove); confirmationEntry = null }
    private fun hideWindows() {
        windowsRequested = false
        closeModelPicker(); closeExitConfirmation(); closeTaskQueue(); closeConversation(); setChromeVisible(false)
    }
    private fun showWindows() {
        windowsRequested = true
        lifecycleScope.launch { refreshSettings(); refreshInbox(); snapAllBalls(); geometry = geometry.clampInto(screenWidth, screenHeight); reconcileWindows() }
    }
    private fun setChromeVisible(visible: Boolean) {
        frameEntry?.view?.visibility = if (visible && conversationEntry == null) View.VISIBLE else View.INVISIBLE
        listOfNotNull(answerEntry, exitEntry, queueBubbleEntry, pickerEntry, inboxEntry, conversationEntry, confirmationEntry).forEach {
            it.view?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        }
    }
    private fun savePositions() {
        val bounds = geometry.serialize()
        val answer = "${answerPoint.x},${answerPoint.y}"
        val exit = "${exitPoint.x},${exitPoint.y}"
        val queue = "${queuePoint.x},${queuePoint.y}"
        container.appScope.launch {
            container.updateSettings { it.copy(overlayBounds = bounds, answerBubblePosition = answer, exitBubblePosition = exit, queueBubblePosition = queue) }
        }
    }
    private fun exit() { savePositions(); hideWindows(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        closeModelPicker(); closeExitConfirmation(); closeTaskQueue()
        snapAllBalls()
        positionConversation()
        geometry = geometry.clampInto(screenWidth, screenHeight)
        frameEntry?.let(host::remove); frameEntry = null
        reconcileWindows()
    }
    override fun onDestroy() {
        OverlayController.markRunning(false); OverlayController.markProjectionReady(false)
        closeModelPicker(); closeExitConfirmation(); closeTaskQueue(); closeConversation()
        conversationStore.clear()
        listOfNotNull(frameEntry, answerEntry, exitEntry, queueBubbleEntry).forEach(host::remove)
        frameEntry = null; answerEntry = null; exitEntry = null; queueBubbleEntry = null
        capture.detach(); runCatching { projection?.stop() }; projection = null
        statusToast?.cancel(); statusToast = null
        // 应用级队列保留已提交的任务，结果继续写入历史会话。
        super.onDestroy()
    }
    override fun onBind(intent: Intent): IBinder? { super.onBind(intent); return null }
    private fun startForegroundWithNotification() {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL_ID).setContentTitle(getString(R.string.service_notification_title))
            .setSmallIcon(android.R.drawable.ic_menu_view).setContentIntent(open).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, notification)
    }
    private fun metrics(): DisplayMetrics = DisplayMetrics().also {
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(it)
    }
    private fun insets(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val inset = getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return inset.top to inset.bottom
        }
        return dp(28) to dp(32)
    }
    private fun parsePoint(value: String?, fallback: FloatingPoint): FloatingPoint {
        val parts = value?.split(',')?.mapNotNull { it.toIntOrNull() }
        return if (parts?.size == 2) FloatingPoint(parts[0], parts[1]) else fallback
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun toast(message: String) {
        statusToast?.cancel()
        statusToast = Toast.makeText(this, message, Toast.LENGTH_SHORT).also { it.show() }
    }
    companion object {
        private const val ANSWER_WIDTH = FloatingSizes.AnswerWidth
        private const val BALL_SIZE = FloatingSizes.Ball
        private const val CHANNEL_ID = "regionx_overlay"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.regionx.app.action.START_OVERLAY"
        const val ACTION_EXIT = "com.regionx.app.action.EXIT_OVERLAY"
        const val ACTION_RESTORE_PROJECTION = "com.regionx.app.action.RESTORE_PROJECTION"
        const val ACTION_HIDE = "com.regionx.app.action.HIDE_OVERLAY"
        const val ACTION_SHOW = "com.regionx.app.action.SHOW_OVERLAY"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, OverlayService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode).putExtra(EXTRA_RESULT_DATA, data)
        fun newSessionIntent(context: Context): Intent =
            Intent(context, OverlayService::class.java).setAction(ACTION_START)
        fun restoreIntent(context: Context, resultCode: Int, data: Intent): Intent =
            startIntent(context, resultCode, data).setAction(ACTION_RESTORE_PROJECTION)
        fun exitIntent(context: Context): Intent = Intent(context, OverlayService::class.java).setAction(ACTION_EXIT)
    }
}
