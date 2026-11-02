package com.regionx.app.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.regionx.app.AppContainer
import com.regionx.app.ImageStore
import com.regionx.app.core.model.ModelConfig
import com.regionx.app.core.model.TaskRecord
import com.regionx.app.core.model.TaskStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class MainViewModelTaskReadTest {
    private lateinit var container: AppContainer
    private lateinit var viewModel: MainViewModel
    private lateinit var server: MockWebServer
    private lateinit var model: ModelConfig
    private lateinit var task: TaskRecord
    private val store = ViewModelStore()

    @Before fun prepare() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer().apply { start() }
        container = AppContainer(RuntimeEnvironment.getApplication())
        container.initialize()
        model = ModelConfig(baseUrl = server.url("/v1").toString(), modelName = "test")
        container.modelRepository.insert(model)
        val session = container.createSession()
        val imagePath = ImageStore(container.database).save(byteArrayOf(1, 2, 3))!!
        task = TaskRecord(sessionId = session.id, modelId = model.id, imagePath = imagePath, title = "Historical title", status = TaskStatus.Running)
        container.database.insertTask(task)
        viewModel = MainViewModel(container)
        store.put("history", viewModel)
        viewModel.openDetail(task.id)
        awaitCondition { viewModel.detail.value?.task?.id == task.id }
    }

    @After fun cleanup() {
        store.clear()
        container.taskQueue.cancelAll()
        container.appScope.cancel()
        server.shutdown()
        Dispatchers.resetMain()
    }

    private suspend fun awaitCondition(predicate: () -> Boolean) {
        withTimeout(5000) { while (!predicate()) delay(10) }
    }

    private suspend fun completeTask() {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Finished answer\"},\"finish_reason\":null}]}\n\ndata: [DONE]\n\n"))
        container.taskQueue.enqueue(task, model)
        awaitCondition { viewModel.detail.value?.task?.status == TaskStatus.Completed }
    }

    @Test fun completionOfAHiddenCurrentDetailRemainsUnread() = runBlocking {
        viewModel.setDetailVisible(false)
        completeTask()

        assertTrue(container.taskReads.isUnread(viewModel.detail.value!!.task))
    }

    @Test fun returningToTheCurrentDetailMarksOnlyItsLatestCompletionRead() = runBlocking {
        viewModel.setDetailVisible(false)
        completeTask()
        val unopened = task.copy(id = "other", status = TaskStatus.Completed)
        assertTrue(container.taskReads.isUnread(viewModel.detail.value!!.task))

        viewModel.setDetailVisible(true)

        awaitCondition { !container.taskReads.isUnread(viewModel.detail.value!!.task) }
        assertTrue(container.taskReads.isUnread(unopened))
    }

    @Test fun completionWhileTheCurrentDetailIsVisibleIsRead() = runBlocking {
        completeTask()

        awaitCondition { !container.taskReads.isUnread(viewModel.detail.value!!.task) }
    }

    @Test fun generatedTitleRemainsSearchableWhenDisplayedTitleIsTheCreationDate() = runBlocking {
        viewModel.reload()
        viewModel.updateSearch("Historical title")
        assertEquals(listOf(task.sessionId), viewModel.filteredSessions().map { it.session.id })

        val collector = launch { viewModel.visibleSessions.collect() }
        try {
            awaitCondition { viewModel.visibleSessions.value.any { it.session.id == task.sessionId } }
        } finally {
            collector.cancelAndJoin()
        }
    }
}
