package com.regionx.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.regionx.app.overlay.OverlayController
import com.regionx.app.overlay.OverlayService
import com.regionx.app.overlay.OverlayWindowHost
import com.regionx.app.platform.ScreenCapturePermission
import com.regionx.app.ui.MainViewModel
import com.regionx.app.ui.SessionsScreen
import com.regionx.app.ui.SettingsScreen
import com.regionx.app.ui.SettingsViewModel
import com.regionx.app.ui.TaskDetailScreen
import com.regionx.app.ui.theme.RegionXTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 主界面：历史会话 / 任务详情 / 设置三个页面在同一 Activity 内切换（对应 Windows 端
 * 「点击任务不再弹独立窗口」的做法）。「开始识别」走授权链：
 * 悬浮窗权限 → 屏幕录制授权 → 前台服务。
 */
class MainActivity : ComponentActivity() {

    private val container: AppContainer by lazy { (application as RegionXApp).container }

    private val mainViewModel: MainViewModel by viewModels { (application as RegionXApp).viewModelFactory }
    private val settingsViewModel: SettingsViewModel by viewModels { (application as RegionXApp).viewModelFactory }

    /** 当前页面：会话列表 / 设置。详情由 MainViewModel.detail 决定。 */
    private val screen = MutableStateFlow(Screen.Sessions)

    private var startOverlayFlow by mutableStateOf(false)

    /** 识别框（前台服务）是否正在运行，用于复用有效的录屏授权。 */
    private val overlayRunning: Boolean get() = OverlayController.isRunning(this)

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val intent = OverlayService.startIntent(this, result.resultCode, data)
            ContextCompat.startForegroundService(this, intent)
            moveTaskToBack(true)
        } else {
            toast("未授权屏幕录制，无法截取识别框内容。")
        }
        startOverlayFlow = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            runCatching { container.initialize() }
                .onFailure { container.logError(it) }
        }

        setContent {
            val settings by container.settings.collectAsStateWithLifecycle()
            RegionXTheme(settings.themeName) {
                AppRoot()
            }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
        if (taskId != null) {
            screen.value = Screen.Sessions
            mainViewModel.openDetail(taskId)
            intent.removeExtra(EXTRA_TASK_ID)
        }
    }

    @Composable
    private fun AppRoot() {
        val detail by mainViewModel.detail.collectAsStateWithLifecycle()
        val visibleSessions by mainViewModel.visibleSessions.collectAsStateWithLifecycle()
        val running by OverlayController.runningState.collectAsStateWithLifecycle()
        val projectionReady by OverlayController.projectionReady.collectAsStateWithLifecycle()
        val expandedId by mainViewModel.expandedSessionId.collectAsStateWithLifecycle()
        val sessionTasks by mainViewModel.sessionTasks.collectAsStateWithLifecycle()
        val activeModelName by mainViewModel.activeModelName.collectAsStateWithLifecycle()
        val searchText by mainViewModel.searchText.collectAsStateWithLifecycle()
        val currentScreen by screen.collectAsStateWithLifecycle()
        val appSettings by container.settings.collectAsStateWithLifecycle()

        // 设置页的数据
        val models by settingsViewModel.models.collectAsStateWithLifecycle()
        val activeModelId by settingsViewModel.activeModelId.collectAsStateWithLifecycle()
        val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
        val status by settingsViewModel.status.collectAsStateWithLifecycle()
        val isError by settingsViewModel.isError.collectAsStateWithLifecycle()
        val isSaving by settingsViewModel.isSaving.collectAsStateWithLifecycle()

        BackHandler(enabled = detail != null || currentScreen == Screen.Settings) {
            if (detail != null) mainViewModel.closeDetail() else screen.value = Screen.Sessions
        }
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            when {
                detail != null -> {
                    val current = detail!!
                    TaskDetailScreen(
                        detail = current,
                        imageFile = container.resolveImage(current.task.imagePath),
                        includeImage = appSettings.includeImageOnFollowUp,
                        onBack = { mainViewModel.closeDetail() },
                        onRetry = { mainViewModel.retryFromDetail() },
                        onSendFollowUp = { text -> mainViewModel.followUp(text) },
                        onStopFollowUp = { mainViewModel.cancelFollowUp() },
                        onToggleIncludeImage = { value ->
                            lifecycleScope.launch {
                                container.updateSettings { it.copy(includeImageOnFollowUp = value) }
                            }
                        },
                    )
                }
                currentScreen == Screen.Settings -> {
                    SettingsScreen(
                        viewModel = settingsViewModel,
                        models = models,
                        activeModelId = activeModelId,
                        settings = settings,
                        status = status,
                        isError = isError,
                        isSaving = isSaving,
                        onBack = {
                            screen.value = Screen.Sessions
                            lifecycleScope.launch { mainViewModel.reload() }
                        },
                    )
                }
                else -> {
                    SessionsScreen(
                        viewModel = mainViewModel,
                        sessions = visibleSessions,
                        expandedSessionId = expandedId,
                        sessionTasks = sessionTasks,
                        activeModelName = activeModelName,
                        searchText = searchText,
                        onStart = { beginStartOverlay() },
                        overlayRunning = running,
                        needsAuthorization = running && !projectionReady,
                        onOpenSettings = {
                            screen.value = Screen.Settings
                            lifecycleScope.launch { settingsViewModel.reload() }
                        },
                    )
                }
            }
        }

        // 设置页数据随设置流刷新
        LaunchedEffect(currentScreen) {
            if (currentScreen == Screen.Settings) settingsViewModel.reload()
        }
    }

    /** 「开始识别」：悬浮窗权限 → 屏幕录制授权 → 启动服务。 */
    private fun beginStartOverlay() {
        if (startOverlayFlow) return
        startOverlayFlow = true

        if (!OverlayWindowHost.canDrawOverlays(this)) {
            toast("请先允许「显示在其他应用上层」，否则识别框无法显示。")
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            startOverlayFlow = false
            return
        }

        // 每次点击都新建历史会话；可用的录屏授权继续复用。
        if (overlayRunning && OverlayController.projectionReady.value) {
            startService(OverlayService.newSessionIntent(this))
            moveTaskToBack(true)
            startOverlayFlow = false
            return
        }

        lifecycleScope.launch {
            try {
                container.initialize()
                if (container.activeModel() == null) {
                    toast("请先在设置中添加一条模型配置。")
                    screen.value = Screen.Settings
                    startOverlayFlow = false
                    return@launch
                }
                projectionLauncher.launch(ScreenCapturePermission.createIntent(this@MainActivity))
            } catch (ex: Exception) {
                startOverlayFlow = false
                container.logError(ex)
                toast("无法打开识别框：${ex.message}")
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        mainViewModel.setDetailVisible(true)
        // 主界面在前台时先收起识别框，避免挡住历史会话（服务与任务不受影响）。
        if (overlayRunning) OverlayController.hideTemporarily(this)
        lifecycleScope.launch { runCatching { mainViewModel.reload() } }
    }

    override fun onPause() {
        mainViewModel.setDetailVisible(false)
        super.onPause()
    }

    private enum class Screen { Sessions, Settings }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
    }
}
