package com.regionx.app.overlay

import com.regionx.app.RegionXApp
import com.regionx.app.R
import android.content.res.Resources
import com.regionx.app.core.model.TaskRecord
import com.regionx.app.core.model.TaskStatus
import com.regionx.app.data.TaskReadStore
import com.regionx.app.ui.TaskUi
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = RegionXApp::class, manifest = Config.NONE, shadows = [QueueReadTest.ServiceResources::class])
class QueueReadTest {
    // These tests exercise service state without packaged Android resources.
    // Supply only the channel labels required by onCreate; other resources stay real.
    @Implements(Resources::class)
    class ServiceResources {
        @RealObject private lateinit var resources: Resources
        @Implementation fun getString(id: Int): String = when (id) {
            R.string.service_channel_name -> "Capture service"
            R.string.service_channel_description -> "Capture service tests"
            else -> Shadow.directlyOn(resources, Resources::class.java, "getString", ClassParameter.from(Int::class.javaPrimitiveType, id))
        }
    }

    private val controller = Robolectric.buildService(OverlayService::class.java)
    private lateinit var service: OverlayService
    private lateinit var reads: TaskReadStore
    private val completed = TaskRecord(id = "first", status = TaskStatus.Completed, updatedAt = 100)
    private val otherCompleted = completed.copy(id = "second")
    private val running = completed.copy(id = "running", status = TaskStatus.Running)

    @Before fun prepare() {
        service = controller.create().get()
        reads = (RuntimeEnvironment.getApplication() as RegionXApp).container.taskReads
        method("setSessionTasks", List::class.java).invoke(service, listOf(completed, otherCompleted, running).map { TaskUi(it) })
        setField("inboxRequested", true)
        setField("inboxEntry", OverlayWindowHost.Entry(service, "taskQueue", OverlayWindowHost.baseParams(173, 187, 0, 0)))
    }

    @After fun cleanup() {
        controller.destroy()
        (RuntimeEnvironment.getApplication() as RegionXApp).container.appScope.cancel()
    }

    @Test fun closingWithTheQueueBubbleReadsAllCurrentCompletionsAndPersistsThem() {
        method("toggleTaskQueue").invoke(service)

        val restored = TaskReadStore(service)
        assertFalse(restored.isUnread(completed))
        assertFalse(restored.isUnread(otherCompleted))
        assertTrue(restored.isUnread(completed.copy(id = "another-session")))
        assertTrue(restored.isUnread(running.copy(status = TaskStatus.Completed)))
        assertTrue(restored.isUnread(completed.copy(updatedAt = 200)))
    }

    @Test fun closingWithTheCloseButtonAlsoReadsAllCurrentCompletions() {
        method("dismissTaskQueue").invoke(service)

        assertFalse(reads.isUnread(completed))
        assertFalse(reads.isUnread(otherCompleted))
    }

    @Test fun internallyHidingTheQueueDoesNotConsumeUnreadResults() {
        method("closeTaskQueue").invoke(service)

        assertTrue(reads.isUnread(completed))
        assertTrue(reads.isUnread(otherCompleted))
        // A second close after the window is gone must not consume anything.
        method("dismissTaskQueue").invoke(service)
        assertTrue(reads.isUnread(completed))
    }

    private fun method(name: String, vararg types: Class<*>) =
        OverlayService::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }

    private fun setField(name: String, value: Any) =
        OverlayService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
}
