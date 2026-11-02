package com.regionx.app.overlay

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 识别框的开关助手：主界面通过它启动 / 收起 / 关闭识别框。
 *
 * 「是否在运行」不再依赖 `getRunningServices`（Android 8+ 起对第三方应用已不可靠），
 * 而是在服务 onCreate/onDestroy 时记录到进程内的静态标记——主界面与服务同进程，
 * 因此这个标记与实际状态一致。
 */
object OverlayController {

    private val _running = MutableStateFlow(false)
    val runningState = _running.asStateFlow()
    private val _projectionReady = MutableStateFlow(false)
    val projectionReady = _projectionReady.asStateFlow()
    private val running: Boolean get() = _running.value

    internal fun markRunning(value: Boolean) {
        _running.value = value
        if (!value) _projectionReady.value = false
    }

    internal fun markProjectionReady(value: Boolean) { _projectionReady.value = value }

    /** 识别框是否在运行（前台服务存活即视为打开）。 */
    fun isRunning(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = running

    /** 主界面回到前台时暂时隐藏识别框（截图与任务不受影响），避免遮挡内容。 */
    fun hideTemporarily(context: Context) {
        if (!running) return
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_HIDE
        }
        runCatching { context.startService(intent) }
    }

    /** 重新显示识别框（用户点了「识别框运行中」时）。 */
    fun showAgain(context: Context) {
        if (!running) return
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SHOW
        }
        runCatching { ContextCompat.startForegroundService(context, intent) }
    }

    fun stop(context: Context) {
        if (!running) return
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_EXIT
        }
        runCatching { context.startService(intent) }
    }
}
