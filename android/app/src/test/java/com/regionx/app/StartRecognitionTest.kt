package com.regionx.app

import com.regionx.app.core.model.ModelConfig
import com.regionx.app.overlay.OverlayController
import com.regionx.app.overlay.OverlayService
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import android.app.Activity
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = RegionXApp::class, manifest = Config.NONE)
class StartRecognitionTest {
    @Test fun startAfterExitCreatesANewSessionInsteadOfResumingTheSavedOne() = runBlocking {
        val application = RuntimeEnvironment.getApplication() as RegionXApp
        val container = application.container
        container.initialize()
        container.modelRepository.insert(ModelConfig(name = "test", modelName = "test"))
        container.setOverlaySession(container.createSession().id)
        ShadowSettings.setCanDrawOverlays(true)
        OverlayController.markRunning(false)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
        try {
            MainActivity::class.java.getDeclaredMethod("beginStartOverlay").apply { isAccessible = true }
                .invoke(controller.get())
            val activity = shadowOf(controller.get())
            withTimeout(5000) {
                while (activity.peekNextStartedActivityForResult() == null) {
                    shadowOf(android.os.Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            val permission = activity.nextStartedActivityForResult
            activity.receiveResult(permission.intent, Activity.RESULT_OK, Intent())
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(OverlayService.ACTION_START, shadowOf(application).nextStartedService.action)
        } finally {
            controller.destroy()
            container.appScope.cancel()
        }
    }

    @Test fun startWhileProjectionIsReadyRequestsANewSessionInsteadOfShowingTheOldOne() = runBlocking {
        val application = RuntimeEnvironment.getApplication() as RegionXApp
        val container = application.container
        container.initialize()
        container.modelRepository.insert(ModelConfig(name = "test", modelName = "test"))
        val previous = container.createSession()
        container.setOverlaySession(previous.id)
        ShadowSettings.setCanDrawOverlays(true)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            OverlayController.markRunning(true)
            OverlayController.markProjectionReady(true)
            MainActivity::class.java.getDeclaredMethod("beginStartOverlay").apply { isAccessible = true }
                .invoke(controller.get())
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val command = shadowOf(application).nextStartedService
            assertNotNull("Start recognition must dispatch a new recognition session", command)
            assertEquals(OverlayService.ACTION_START, command.action)
        } finally {
            OverlayController.markRunning(false)
            controller.destroy()
            container.appScope.cancel()
        }
    }
}
