package com.regionx.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/** 识别框在屏幕上的矩形（物理像素）。 */
data class OverlayGeometry(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val rect: Rect get() = Rect(left, top, right, bottom)

    /** 整体限制在屏幕内，并保证不小于 [MIN_SIZE]。 */
    fun clampInto(screenWidth: Int, screenHeight: Int): OverlayGeometry {
        val maxW = screenWidth.coerceAtLeast(MIN_SIZE)
        val maxH = screenHeight.coerceAtLeast(MIN_SIZE)
        val w = width.coerceIn(MIN_SIZE.coerceAtMost(maxW), maxW)
        val h = height.coerceIn(MIN_SIZE.coerceAtMost(maxH), maxH)
        val l = left.coerceIn(0, (screenWidth - w).coerceAtLeast(0))
        val t = top.coerceIn(0, (screenHeight - h).coerceAtLeast(0))
        return OverlayGeometry(l, t, l + w, t + h)
    }

    fun serialize(): String = "$left,$top,$right,$bottom"

    companion object {
        /** 识别框最小尺寸（像素）：手机屏幕小、手指粗，比 Windows 端留得大。 */
        const val MIN_SIZE = 140

        fun parse(value: String?, screenWidth: Int, screenHeight: Int): OverlayGeometry {
            val parts = value?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
            if (parts == null || parts.size != 4) return default(screenWidth, screenHeight)
            return OverlayGeometry(parts[0], parts[1], parts[2], parts[3])
                .clampInto(screenWidth, screenHeight)
        }

        fun default(screenWidth: Int, screenHeight: Int): OverlayGeometry {
            val w = (screenWidth * 0.78f).toInt().coerceAtLeast(MIN_SIZE).coerceAtMost(screenWidth)
            val h = (screenHeight * 0.30f).toInt().coerceAtLeast(MIN_SIZE).coerceAtMost(screenHeight)
            val left = (screenWidth - w) / 2
            val top = ((screenHeight - h) / 2 - screenHeight / 10).coerceAtLeast(0)
            return OverlayGeometry(left, top, left + w, top + h)
        }
    }
}

/** 悬浮窗宿主：把 Compose 内容挂到 WindowManager，并统一管理窗口参数。 */
class OverlayWindowHost(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    private val savedStateOwner = SimpleSavedStateOwner(lifecycleOwner.lifecycle)

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    fun add(entry: Entry, content: @Composable () -> Unit) {
        val view = ComposeView(entry.context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedStateOwner)
            setContent(content)
        }
        entry.view = view
        windowManager.addView(view, entry.params)
    }

    fun update(entry: Entry) {
        val view = entry.view ?: return
        runCatching { windowManager.updateViewLayout(view, entry.params) }
    }

    fun remove(entry: Entry) {
        val view = entry.view ?: return
        entry.view = null
        runCatching { windowManager.removeViewImmediate(view) }
    }

    /** 一个悬浮窗的窗口参数与视图。 */
    class Entry(
        val context: Context,
        val name: String,
        val params: WindowManager.LayoutParams,
    ) {
        var view: android.view.View? = null
    }

    companion object {
        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

        /**
         * 悬浮窗基础参数：不抢焦点（除需要输入法的弹窗）、按屏幕绝对坐标定位。
         * 注意：`FLAG_NOT_FOCUSABLE` 的窗口不接收输入法，需要输入时传 focusable = true。
         */
        fun baseParams(
            width: Int,
            height: Int,
            x: Int,
            y: Int,
            focusable: Boolean = false,
        ): WindowManager.LayoutParams {
            var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

            return WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x
                this.y = y
            }
        }

        /** 切换窗口的「可触摸」状态（取消置顶时让框内触摸穿透到下层应用）。 */
        fun applyTouchable(params: WindowManager.LayoutParams, touchable: Boolean) {
            params.flags = if (touchable) {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
        }
    }
}

/**
 * 最小可用的 SavedStateRegistryOwner：直接持有控制器并完成一次 restore，
 * 生命周期跟随宿主（服务）。悬浮窗不需要状态恢复。
 */
internal class SimpleSavedStateOwner(
    override val lifecycle: Lifecycle,
) : SavedStateRegistryOwner {
    private val controller = SavedStateRegistryController.create(this)

    init {
        controller.performRestore(null)
    }

    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
}
