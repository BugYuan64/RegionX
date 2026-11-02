package com.regionx.app.platform

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build

/**
 * 屏幕录制授权入口。
 *
 * Android 14 起系统允许在授权弹窗里选择「共享一个应用」，那样 MediaProjection
 * 只会拿到该应用的画面，识别框就截不到桌面内容。识别框需要整屏，
 * 因此在支持的系统上通过 MediaProjectionConfig 预设「整屏共享」。
 */
object ScreenCapturePermission {

    fun createIntent(context: Context): Intent {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return runCatching {
                manager.createScreenCaptureIntent(
                    android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
                )
            }.getOrElse { manager.createScreenCaptureIntent() }
        }
        return manager.createScreenCaptureIntent()
    }
}
