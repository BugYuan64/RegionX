package com.regionx.app.platform

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.regionx.app.core.capture.CaptureDisplayPolicy
import com.regionx.app.core.capture.CaptureGeometry
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resumeWithException

/**
 * A consent session owns one VirtualDisplay, including repeated capture and size fallback.
 * The reader/surface stays attached between screenshots: detaching powers off the virtual
 * display and makes every subsequent request start a new compositor output stream.
 * Keep only the latest image and require a frame produced after the caller hid its overlays.
 */
class ScreenCapture(
    private val windowManager: WindowManager,
    private val onProjectionStopped: () -> Unit = {},
) {
    private class Session(val projection: MediaProjection) {
        val thread = HandlerThread("regionx-capture").apply { start() }
        val handler = Handler(thread.looper)
        val policy = CaptureDisplayPolicy()
        var callback: MediaProjection.Callback? = null
        var display: VirtualDisplay? = null
        var reader: ImageReader? = null
        var latestImage: Image? = null
        var pending: Request? = null
    }

    private class Request(
        val continuation: CancellableContinuation<Bitmap>,
        val notBeforeNanos: Long,
    )

    private val lock = Any()
    private val busy = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var session: Session? = null

    val isReady: Boolean get() = synchronized(lock) { session != null }

    fun attach(mediaProjection: MediaProjection) {
        synchronized(lock) {
            if (session?.projection === mediaProjection) return
            detachLocked()
            val next = Session(mediaProjection)
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    val stopped = synchronized(lock) {
                        if (session !== next) false else {
                            session = null
                            releaseSessionLocked(next, stopProjection = false)
                            true
                        }
                    }
                    if (stopped) notifyProjectionStopped()
                }
            }
            next.callback = callback
            try {
                // Required before createVirtualDisplay when targeting Android 14+.
                mediaProjection.registerCallback(callback, mainHandler)
                session = next
                // Android consent expires if its first VirtualDisplay is created too late.
                // Start this session immediately; subsequent screenshots reuse its live output.
                val metrics = screenMetrics()
                configureReaderLocked(next, metrics.screenWidth, metrics.screenHeight, metrics.densityDpi)
            } catch (ex: Exception) {
                if (session === next) session = null
                releaseSessionLocked(next, stopProjection = true)
                throw CaptureException("无法建立屏幕录制会话，请重新授权。", ex)
            }
        }
    }

    fun detach() {
        synchronized(lock) { detachLocked() }
    }

    private fun detachLocked() {
        val active = session ?: return
        session = null
        releaseSessionLocked(active, stopProjection = true)
    }

    private fun releaseSessionLocked(active: Session, stopProjection: Boolean) {
        active.policy.close()
        active.pending?.let { request ->
            releaseRequestLocked(active, request)
            if (request.continuation.isActive) {
                request.continuation.resumeWithException(CaptureException("屏幕录制授权已失效，请重新授权后再试。"))
            }
        }
        runCatching { active.display?.release() }
        active.display = null
        closeReaderLocked(active)
        active.callback?.let { callback -> runCatching { active.projection.unregisterCallback(callback) } }
        active.callback = null
        if (stopProjection) runCatching { active.projection.stop() }
        active.thread.quitSafely()
    }

    private fun notifyProjectionStopped() {
        mainHandler.post {
            // A queued callback from the old consent must not invalidate a newly attached one.
            if (!isReady) onProjectionStopped()
        }
    }

    data class Metrics(val screenWidth: Int, val screenHeight: Int, val densityDpi: Int)
    data class CapturedScreen(val png: ByteArray, val width: Int, val height: Int)

    fun screenMetrics(): Metrics {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            return Metrics(bounds.width(), bounds.height(), metrics.densityDpi)
        }
        return Metrics(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    /**
     * Capture a physical-screen rectangle as PNG. For static screens, pass the nanoTime taken
     * immediately after hiding overlays, before the caller's composition delay.
     */
    suspend fun captureRegion(
        left: Int, top: Int, right: Int, bottom: Int,
        notBeforeNanos: Long = System.nanoTime(),
    ): ByteArray = withFullScreen(notBeforeNanos) { full, metrics ->
        val crop = CaptureGeometry.mapCrop(
            left, top, right, bottom,
            metrics.screenWidth, metrics.screenHeight, full.width, full.height,
        )
        val cropped = Bitmap.createBitmap(full, crop.left, crop.top, crop.right - crop.left, crop.bottom - crop.top)
        try {
            encodePng(cropped)
        } finally {
            if (cropped !== full) cropped.recycle()
        }
    }

    /** Full-screen mode encodes the complete acquired frame, independent of overlay bounds. */
    suspend fun captureScreen(notBeforeNanos: Long = System.nanoTime()): CapturedScreen =
        withFullScreen(notBeforeNanos) { full, _ ->
            CapturedScreen(encodePng(full), full.width, full.height)
        }

    private suspend fun <T> withFullScreen(notBeforeNanos: Long, consume: (Bitmap, Metrics) -> T): T {
        if (!busy.compareAndSet(false, true)) throw CaptureException("正在截图，请稍候再试。")
        try {
            coroutineContext.ensureActive()
            val active = synchronized(lock) { session }
                ?: throw CaptureException("屏幕录制授权已失效，请重新授权后再试。")
            val metrics = screenMetrics()
            val full = captureFullScreen(active, metrics, notBeforeNanos)
            try {
                coroutineContext.ensureActive()
                val current = screenMetrics()
                if (current.screenWidth != metrics.screenWidth || current.screenHeight != metrics.screenHeight) {
                    throw CaptureException("截图期间屏幕方向发生变化，请重试。")
                }
                val result = consume(full, metrics)
                coroutineContext.ensureActive()
                return result
            } finally {
                full.recycle()
            }
        } finally {
            busy.set(false)
        }
    }

    private fun encodePng(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
            throw CaptureException("无法编码截图，请重试。")
        }
        out.toByteArray()
    }

    private suspend fun captureFullScreen(active: Session, metrics: Metrics, notBeforeNanos: Long): Bitmap {
        for (divisor in listOf(1, 2)) {
            val width = (metrics.screenWidth / divisor).coerceAtLeast(1)
            val height = (metrics.screenHeight / divisor).coerceAtLeast(1)
            // Keep ownership outside withTimeout: a timeout may win just after awaitFrame returns.
            var acquired: Bitmap? = null
            var delivered = false
            try {
                val bitmap = withTimeoutOrNull(2_000) {
                    awaitFrame(active, width, height, metrics.densityDpi, notBeforeNanos).also { acquired = it }
                }
                if (bitmap != null) {
                    delivered = true
                    return bitmap
                }
            } finally {
                if (!delivered) acquired?.recycle()
            }
        }
        throw CaptureException("没有获取到屏幕画面，请重试或重新授权屏幕录制。")
    }

    private suspend fun awaitFrame(active: Session, width: Int, height: Int, densityDpi: Int, notBeforeNanos: Long): Bitmap =
        suspendCancellableCoroutine { continuation ->
            synchronized(lock) {
                if (session !== active) {
                    continuation.resumeWithException(CaptureException("屏幕录制授权已失效，请重新授权后再试。"))
                    return@synchronized
                }
                var request: Request? = null
                try {
                    val pending = Request(continuation, notBeforeNanos)
                    request = pending
                    active.pending = pending
                    continuation.invokeOnCancellation {
                        synchronized(lock) { releaseRequestLocked(active, pending) }
                    }
                    if (!continuation.isActive) return@synchronized
                    configureReaderLocked(active, width, height, densityDpi)
                    deliverLatestLocked(active, pending)
                } catch (ex: Exception) {
                    // Creation can consume a token even when it fails. Never attempt CREATE again.
                    // Clear the request first: session teardown must not resume it with a generic
                    // stopped-session error and hide the platform exception that caused teardown.
                    request?.let { releaseRequestLocked(active, it) }
                    if (session === active) {
                        session = null
                        releaseSessionLocked(active, stopProjection = true)
                        notifyProjectionStopped()
                    }
                    if (continuation.isActive) {
                        continuation.resumeWithException(CaptureException("无法获取屏幕画面，请重新授权后再试。", ex))
                    }
                }
            }
        }

    /** Must hold lock. One CREATE per consent; same-size requests keep the existing surface. */
    private fun configureReaderLocked(active: Session, width: Int, height: Int, densityDpi: Int) {
        val action = active.policy.configure(width, height, densityDpi)
        if (action == CaptureDisplayPolicy.Action.ATTACH) return
        // One cached Image + two spare slots are needed by acquireLatestImage.
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        val previous = active.reader
        active.latestImage?.close()
        active.latestImage = null
        active.reader = reader
        reader.setOnImageAvailableListener({ source -> onFrameAvailable(active, source) }, active.handler)
        try {
            when (action) {
                CaptureDisplayPolicy.Action.CREATE -> {
                    active.display = active.projection.createVirtualDisplay(
                        "regionx-capture", width, height, densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        reader.surface, null, active.handler,
                    ) ?: throw CaptureException("无法创建屏幕录制显示器。")
                }
                CaptureDisplayPolicy.Action.RESIZE -> {
                    val display = checkNotNull(active.display)
                    display.resize(width, height, densityDpi)
                    display.surface = reader.surface
                }
                CaptureDisplayPolicy.Action.ATTACH -> Unit
            }
        } finally {
            runCatching { previous?.setOnImageAvailableListener(null, null) }
            runCatching { previous?.close() }
        }
    }

    private fun onFrameAvailable(active: Session, reader: ImageReader) {
        synchronized(lock) {
            if (session !== active || active.reader !== reader) return
            try {
                val image = reader.acquireLatestImage() ?: return
                active.latestImage?.close()
                active.latestImage = image
                active.pending?.let { deliverLatestLocked(active, it) }
            } catch (ex: Exception) {
                active.pending?.let { request ->
                    releaseRequestLocked(active, request)
                    if (request.continuation.isActive) {
                        request.continuation.resumeWithException(CaptureException("读取屏幕画面失败，请重试。"))
                    }
                }
            }
        }
    }

    private fun deliverLatestLocked(active: Session, request: Request) {
        if (active.pending !== request || !request.continuation.isActive) return
        val image = active.latestImage ?: return
        // The MediaProjection compositor uses CLOCK_MONOTONIC nanoseconds (AOSP
        // VirtualDisplaySurface::onFrameCommitted / Surface::queueBuffer), as does nanoTime.
        // A cached frame produced during the caller's hide-delay is valid even on a static screen.
        if (image.timestamp < request.notBeforeNanos) return
        val bitmap = imageToBitmap(image)
        releaseRequestLocked(active, request)
        request.continuation.resume(bitmap) { _, value, _ -> value.recycle() }
    }

    /** Must hold lock. Idempotent because cancellation, frame delivery and onStop can race. */
    private fun releaseRequestLocked(active: Session, request: Request) {
        if (active.pending !== request) return
        active.pending = null
    }

    private fun closeReaderLocked(active: Session) {
        runCatching { active.latestImage?.close() }
        active.latestImage = null
        runCatching { active.reader?.setOnImageAvailableListener(null, null) }
        runCatching { active.reader?.close() }
        active.reader = null
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        check(plane.pixelStride == 4) { "Unsupported RGBA pixel stride" }
        val width = image.width
        val height = image.height
        val source = plane.buffer.duplicate().apply { rewind() }
        val packed = if (plane.rowStride == width * 4) source else {
            // Copy actual pixels only; the last row's padding need not be present in the buffer.
            ByteBuffer.allocateDirect(width * height * 4).apply {
                repeat(height) { row ->
                    val pixels = source.duplicate()
                    pixels.position(row * plane.rowStride)
                    pixels.limit(row * plane.rowStride + width * 4)
                    put(pixels)
                }
                rewind()
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.copyPixelsFromBuffer(packed)
            return bitmap
        } catch (ex: Throwable) {
            bitmap.recycle()
            throw ex
        }
    }
}

class CaptureException(message: String, cause: Throwable? = null) : Exception(message, cause)
