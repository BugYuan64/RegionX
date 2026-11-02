package com.regionx.app

import android.app.Application
import com.regionx.app.core.model.ModelConfig
import com.regionx.app.core.model.TaskKind
import com.regionx.app.core.model.TaskStatus
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class UpstreamSelectionTest {
    @Test fun switchingUpstreamsClearsOnlyTheRuntimeModelSelection() = runBlocking {
        val container = AppContainer(RuntimeEnvironment.getApplication())
        try {
            container.modelRepository.insert(ModelConfig(id = "one", modelName = "default-one"))
            container.modelRepository.insert(ModelConfig(id = "two", modelName = "default-two"))
            container.selectUpstream("one")
            container.selectUpstreamModel("catalog-one")
            container.selectUpstream("one")
            assertEquals("catalog-one", container.activeModel()?.modelName)
            container.selectUpstream("two")
            assertEquals("two", container.activeModel()?.id)
            assertEquals("default-two", container.activeModel()?.modelName)
            assertNull(container.settings.value.activeUpstreamModelName)
            assertEquals(2, container.modelRepository.all().size)
            try {
                container.selectUpstreamModel("old-choice", "one")
                fail("Stale menu must not change a different upstream")
            } catch (_: IllegalArgumentException) { }
            assertEquals("default-two", container.activeModel()?.modelName)
        } finally { container.appScope.cancel() }
    }

    @Test fun queuedTaskRetryAndFollowUpKeepTheSubmittedCatalogModel() = runBlocking {
        val container = AppContainer(RuntimeEnvironment.getApplication())
        val server = MockWebServer().apply { start() }
        try {
            val model = ModelConfig(id = "upstream", name = "friendly upstream", modelName = "default-model",
                baseUrl = server.url("/v1").toString())
            container.modelRepository.insert(model)
            container.selectUpstream(model.id)
            container.selectUpstreamModel("choice-a")
            repeat(3) { server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"answer\"},\"finish_reason\":null}]}\n\ndata: [DONE]\n\n")) }
            val task = container.enqueueTask(container.createSession(), TaskKind.Answer, byteArrayOf(1, 2, 3),
                RegionBounds(0, 0, 10, 10), container.activeModel()!!)!!
            container.selectUpstreamModel("choice-b")
            suspend fun completed() { withTimeout(5000) { while (container.task(task.id)?.status != TaskStatus.Completed) delay(10) } }
            completed()
            assertEquals("choice-a", container.task(task.id)?.modelName)
            container.retryTask(task.id)
            completed()
            container.followUp(container.task(task.id)!!, "why") { }
            repeat(3) {
                val request = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
                assertEquals("choice-a", Json.parseToJsonElement(request.body.readUtf8()).jsonObject["model"]?.jsonPrimitive?.content)
            }
            assertEquals("choice-b", container.activeModel()?.modelName)
        } finally { container.taskQueue.cancelAll(); container.appScope.cancel(); server.shutdown() }
    }

    @Test fun selectedCatalogModelOverridesOnlyTheActiveUpstream() = runBlocking {
        val container = AppContainer(RuntimeEnvironment.getApplication())
        try {
            val first = ModelConfig(id = "first", name = "upstream one", modelName = "default-one")
            val second = ModelConfig(id = "second", name = "upstream two", modelName = "default-two")
            container.modelRepository.insert(first)
            container.modelRepository.insert(second)
            container.database.writeSettingsFile("""{"ActiveModelId":"second","ActiveUpstreamModelName":"catalog-choice"}""")
            container.initialize()
            assertEquals("second", container.activeModel()?.id)
            assertEquals("catalog-choice", container.activeModel()?.modelName)
            assertEquals("default-two", container.modelRepository.get("second")?.modelName)
            assertEquals("default-one", container.modelRepository.get("first")?.modelName)
        } finally { container.appScope.cancel() }
    }

    @Test fun missingUpstreamCannotReuseAnotherUpstreamsCatalogChoice() = runBlocking {
        val container = AppContainer(RuntimeEnvironment.getApplication())
        try {
            container.modelRepository.insert(ModelConfig(id = "remaining", modelName = "safe-default"))
            container.database.writeSettingsFile("""{"ActiveModelId":"deleted","ActiveUpstreamModelName":"private-model"}""")
            container.initialize()
            assertEquals("safe-default", container.activeModel()?.modelName)
        } finally { container.appScope.cancel() }
    }
}
