package com.regionx.app.platform

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow

/** Lifecycle regression only: compositor pixel correctness still requires an Android device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, shadows = [ScreenCaptureSessionTest.Projection::class])
class ScreenCaptureSessionTest {
    @Implements(MediaProjection::class)
    class Projection {
        var display: VirtualDisplay? = null
        var creates = 0
        var createFailure: RuntimeException? = null

        @Implementation
        fun registerCallback(callback: MediaProjection.Callback, handler: Handler?) = Unit

        @Implementation
        fun unregisterCallback(callback: MediaProjection.Callback) = Unit

        @Implementation
        fun stop() = Unit

        @Implementation
        fun createVirtualDisplay(
            name: String, width: Int, height: Int, dpi: Int, flags: Int,
            surface: Surface?, callback: VirtualDisplay.Callback?, handler: Handler?,
        ): VirtualDisplay {
            creates++
            createFailure?.let { throw it }
            return (RuntimeEnvironment.getApplication().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .createVirtualDisplay(name, width, height, dpi, surface, flags, callback, handler)
                .also { display = it }
        }
    }

    @Test fun attachingConsentStartsTheDisplayBeforeAnyScreenshotRequest() {
        val context = RuntimeEnvironment.getApplication()
        val capture = ScreenCapture(context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
        val projection = Shadow.newInstanceOf(MediaProjection::class.java)
        val source = Shadow.extract<Projection>(projection)
        try {
            capture.attach(projection)
            assertEquals("Consume consent while fresh instead of waiting for the first answer", 1, source.creates)
            assertNotNull(source.display?.surface)
        } finally { capture.detach() }
    }

    @Test fun displayCreationFailureKeepsTheOriginalPlatformCause() {
        val context = RuntimeEnvironment.getApplication()
        val capture = ScreenCapture(context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
        val projection = Shadow.newInstanceOf(MediaProjection::class.java)
        val source = Shadow.extract<Projection>(projection)
        val original = SecurityException("platform rejected projection token")
        source.createFailure = original
        try {
            val error = runCatching { capture.attach(projection) }.exceptionOrNull()
            assertTrue(error is CaptureException)
            assertSame("Session cleanup must not replace the useful creation exception", original, error?.cause)
            assertFalse(capture.isReady)
        } finally { capture.detach() }
    }

    @Test fun cancellingARequestKeepsItsSurfaceAttachedForTheNextScreenshot() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val capture = ScreenCapture(context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
        val projection = Shadow.newInstanceOf(MediaProjection::class.java)
        val source = Shadow.extract<Projection>(projection)
        capture.attach(projection)
        try {
            val first = launch(start = CoroutineStart.UNDISPATCHED) { capture.captureRegion(0, 0, 10, 10) }
            val display = checkNotNull(source.display)
            val surface = checkNotNull(display.surface)
            first.cancelAndJoin()
            assertSame("Finishing a request must not power off the virtual display", surface, display.surface)

            val second = launch(start = CoroutineStart.UNDISPATCHED) { capture.captureRegion(0, 0, 10, 10) }
            try {
                assertEquals("One display per consent", 1, source.creates)
                assertSame("Same-size requests must keep the same ImageReader surface", surface, display.surface)
            } finally { second.cancelAndJoin() }
        } finally { capture.detach() }
    }
}
