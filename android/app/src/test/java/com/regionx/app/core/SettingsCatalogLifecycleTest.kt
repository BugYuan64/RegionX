package com.regionx.app.core

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.regionx.app.AppContainer
import com.regionx.app.core.model.LlmProvider
import com.regionx.app.core.model.ModelConfig
import com.regionx.app.ui.ModelEditor
import com.regionx.app.ui.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class SettingsCatalogLifecycleTest {
    private lateinit var container: AppContainer
    private lateinit var viewModel: SettingsViewModel
    private lateinit var server: MockWebServer
    private val store = ViewModelStore()

    @Before fun prepare() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer().apply { start() }
        container = AppContainer(RuntimeEnvironment.getApplication())
        container.initialize()
        viewModel = SettingsViewModel(container)
        store.put("settings", viewModel)
        viewModel.reload()
    }

    @After fun cleanup() = runBlocking {
        store.clear()
        container.taskQueue.cancelAll()
        container.appScope.cancel()
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun config() = ModelConfig(id = "upstream-a", name = "A", baseUrl = server.url("/v1").toString(), modelName = "default-a")

    private fun response(id: String, delayMs: Long = 150) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"data":[{"id":"$id"}]}""")
        .setBodyDelay(delayMs, TimeUnit.MILLISECONDS)

    private suspend fun rejectsLateCatalog(changeEditor: () -> Unit) {
        viewModel.selectModel(config())
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                check(release.await(3, TimeUnit.SECONDS)) { "Test did not release catalog response" }
                return response("old-upstream-model", delayMs = 0)
            }
        }
        viewModel.loadCatalog()
        assertNotNull("Catalog request did not arrive", server.takeRequest(3, TimeUnit.SECONDS))
        try { changeEditor() } finally { release.countDown() }
        val stale = withTimeoutOrNull(600) {
            viewModel.catalog.first { models -> models.any { it.id == "old-upstream-model" } }
        }
        assertNull("A previous editor's catalog must not populate the current editor", stale)
        assertNull(viewModel.catalogError.value)
        assertFalse(viewModel.catalogLoading.value)
    }

    @Test fun switchingEditorsRejectsTheOldUpstreamResponse() = runBlocking {
        rejectsLateCatalog { viewModel.selectModel(config().copy(id = "upstream-b", name = "B")) }
        assertEquals("upstream-b", viewModel.editor.value?.id)
    }

    @Test fun closingEditorRejectsTheOldUpstreamResponse() = runBlocking {
        rejectsLateCatalog { viewModel.closeEditor() }
        assertNull(viewModel.editor.value)
    }

    @Test fun addingConfigRejectsTheOldUpstreamResponse() = runBlocking {
        rejectsLateCatalog { viewModel.addModel() }
        assertEquals(true, viewModel.editor.value?.isNew)
    }

    @Test fun changingConnectionFieldsRejectsTheOldUpstreamResponse() = runBlocking {
        val changes: List<(ModelEditor) -> ModelEditor> = listOf(
            { it.copy(provider = LlmProvider.Claude) },
            { it.copy(baseUrl = server.url("/other/v1").toString()) },
            { it.copy(apiKey = "replacement-test-key") },
            { it.copy(extraHeaders = "X-Endpoint: replacement") },
        )
        changes.forEach { change -> rejectsLateCatalog { viewModel.updateEditor(change) } }
    }

    @Test fun choosingAModelDoesNotCancelItsCurrentCatalogRequest() = runBlocking {
        viewModel.selectModel(config())
        server.enqueue(response("available-model"))
        viewModel.loadCatalog()
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        viewModel.updateEditor { it.copy(modelName = "typed-model") }
        val result = withTimeout(3000) { viewModel.catalog.first { it.isNotEmpty() } }
        assertEquals(listOf("available-model"), result.map { it.id })
        assertEquals("typed-model", viewModel.editor.value?.modelName)
    }

    @Test fun anOldRequestCannotFinishTheNewerLoadingState() = runBlocking {
        viewModel.selectModel(config())
        val releaseOld = CountDownLatch(1)
        val requests = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (requests.incrementAndGet() == 1) {
                    check(releaseOld.await(3, TimeUnit.SECONDS)) { "Test did not release old request" }
                    return response("old-upstream-model", delayMs = 0)
                }
                return response("new-upstream-model", delayMs = 700)
            }
        }
        viewModel.loadCatalog()
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        viewModel.loadCatalog()
        try { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) } finally { releaseOld.countDown() }
        val prematureFinish = withTimeoutOrNull(350) { viewModel.catalogLoading.first { !it } }
        assertNull("The old request must not hide the new request's loading indicator", prematureFinish)
        val current = withTimeout(3000) { viewModel.catalog.first { models -> models.any { it.id == "new-upstream-model" } } }
        assertEquals(listOf("new-upstream-model"), current.map { it.id })
        assertNull(viewModel.catalogError.value)
    }
}
