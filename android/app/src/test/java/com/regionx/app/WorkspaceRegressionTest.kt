package com.regionx.app

import android.app.Application
import com.regionx.app.core.model.*
import com.regionx.app.ui.MainViewModel
import kotlinx.coroutines.*
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
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class WorkspaceRegressionTest {
    private lateinit var container: AppContainer
    private lateinit var server: MockWebServer
    private lateinit var model: ModelConfig
    private lateinit var task: TaskRecord

    @Before fun prepare() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer().apply { start() }
        container = AppContainer(RuntimeEnvironment.getApplication())
        container.initialize()
        model = ModelConfig(name = "test", baseUrl = server.url("/v1").toString().trimEnd('/'), modelName = "test")
        container.modelRepository.insert(model)
        val session = container.createSession()
        val imagePath = ImageStore(container.database).save(byteArrayOf(1, 2, 3))!!
        task = TaskRecord(sessionId = session.id, modelId = model.id, imagePath = imagePath, status = TaskStatus.Queued)
        container.database.insertTask(task)
        container.database.insertMessage(MessageRecord(taskId = task.id, role = ChatRole.User, content = "original question", createdAt = 1))
        Unit
    }

    @After fun cleanup() = runBlocking {
        container.taskQueue.cancelAll()
        container.appScope.cancel()
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun answer(text: String): MockResponse = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"$text\"},\"finish_reason\":null}]}\n\ndata: [DONE]\n\n")

    private suspend fun awaitCondition(predicate: suspend () -> Boolean) {
        withTimeout(5000) { while (!predicate()) delay(10) }
    }

    @Test fun retryReplacesPrimaryAnswerAndPreservesFollowUps() = runBlocking {
        container.database.updateTask(task.copy(status = TaskStatus.Failed))
        container.database.insertMessage(MessageRecord(taskId = task.id, role = ChatRole.Assistant, content = "old partial", createdAt = 2))
        container.database.insertMessage(MessageRecord(taskId = task.id, role = ChatRole.User, content = "follow up", createdAt = 3))
        container.database.insertMessage(MessageRecord(taskId = task.id, role = ChatRole.Assistant, content = "follow up answer", createdAt = 4))
        server.enqueue(answer("replacement"))
        container.retryTask(task.id)
        awaitCondition { container.task(task.id)?.status == TaskStatus.Completed }
        val messages = container.messages(task.id)
        assertEquals("replacement", messages.first { it.role == ChatRole.Assistant }.content)
        assertEquals(listOf("replacement", "follow up answer"), messages.filter { it.role == ChatRole.Assistant }.map { it.content })
    }

    @Test fun detailReceivesAnswerWhenAnOpenTaskCompletes() = runBlocking {
        val viewModel = MainViewModel(container)
        viewModel.openDetail(task.id)
        awaitCondition { viewModel.detail.value?.task?.id == task.id }
        server.enqueue(answer("finished answer"))
        container.taskQueue.enqueue(task, model)
        awaitCondition { viewModel.detail.value?.task?.status == TaskStatus.Completed }
        assertEquals("finished answer", viewModel.detail.value?.mainAnswer)
        viewModel.closeDetail()
    }

    @Test fun stoppedFollowUpKeepsItsPartialAnswerInHistory() = runBlocking {
        // MockWebServer's response throttle also throttles request uploads. Keep its
        // first period larger than the full prompt so this tests a response interruption.
        val first = ":" + " ".repeat(8192) + "\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":null}]}\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(first + ":".repeat(first.length) + "\n\ndata: [DONE]\n\n")
            .throttleBody(first.length.toLong(), 1, TimeUnit.SECONDS))
        val received = CompletableDeferred<Unit>()
        val job = launch { container.followUp(task, "follow up") { if (it.contains("partial")) received.complete(Unit) } }
        try {
            withTimeout(5000) { received.await() }
        } catch (ex: TimeoutCancellationException) {
            throw AssertionError("No partial callback: requests=${server.requestCount}, active=${job.isActive}", ex)
        }
        job.cancelAndJoin()
        val saved = container.messages(task.id).filter { it.role == ChatRole.Assistant }
        assertTrue("Partial assistant reply must survive reopening the detail", saved.any { it.content.contains("partial") && it.content.contains("已停止") })
    }

    @Test fun initializeDoesNotRecoverTasksAgainInTheSameProcess() = runBlocking {
        container.database.updateTask(task.copy(status = TaskStatus.Running))
        container.initialize()
        assertEquals(TaskStatus.Running, container.task(task.id)?.status)
    }

    @Test fun completedAndFailedTasksDoNotPostResultNotifications() = runBlocking {
        val manager = container.appContext.getSystemService(android.app.NotificationManager::class.java)
        manager.cancelAll()
        server.enqueue(answer("finished answer"))
        container.taskQueue.enqueue(task, model)
        awaitCondition { container.task(task.id)?.status == TaskStatus.Completed }
        assertTrue("Completed tasks must be available in the queue without notifications", manager.activeNotifications.isEmpty())

        server.enqueue(MockResponse().setResponseCode(500).setBody("test failure"))
        container.retryTask(task.id)
        awaitCondition { container.task(task.id)?.status == TaskStatus.Failed }
        assertTrue("Failed tasks must not post notifications either", manager.activeNotifications.isEmpty())
    }

    @Test fun cancellationAfterFinalDeltaStillSavesTheWholeReply() = runBlocking {
        server.enqueue(answer("whole answer"))
        var callbacks = 0
        lateinit var job: Job
        job = launch(start = CoroutineStart.LAZY) {
            container.followUp(task, "follow up") {
                if (++callbacks == 2) job.cancel()
            }
        }
        job.start()
        withTimeout(10000) { job.join() }
        assertTrue(container.messages(task.id).any { it.role == ChatRole.Assistant && it.content.contains("whole answer") })
    }

    @Test fun followUpSendsOriginalQuestionBeforePrimaryAnswerDespiteTimestampSkew() = runBlocking {
        val other = task.copy(id = newId(), createdAt = 1)
        container.database.insertTask(other)
        container.database.insertMessage(MessageRecord(taskId = other.id, role = ChatRole.User, content = "initial", createdAt = 10))
        container.database.savePrimaryAnswer(other, "primary")
        server.enqueue(answer("follow up answer"))
        container.followUp(other, "next") { }
        val request = withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) }!!
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val roles = body.getValue("messages").jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content }.filter { it != "system" }
        assertEquals(listOf("user", "assistant", "user"), roles)
    }

    @Test fun primaryCompletionRefreshesDetailWhileAFollowUpIsStreaming() = runBlocking {
        val viewModel = MainViewModel(container)
        viewModel.openDetail(task.id)
        awaitCondition { viewModel.detail.value?.task?.id == task.id }
        server.enqueue(answer("primary answer").setBodyDelay(300, TimeUnit.MILLISECONDS))
        container.taskQueue.enqueue(task, model)
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
        server.enqueue(answer("follow up answer").setBodyDelay(1000, TimeUnit.MILLISECONDS))
        viewModel.followUp("question while primary is running")
        awaitCondition { viewModel.detail.value?.isStreaming == true }
        awaitCondition { viewModel.detail.value?.isStreaming == false }
        assertEquals(TaskStatus.Completed, viewModel.detail.value?.task?.status)
        assertEquals("primary answer", viewModel.detail.value?.mainAnswer)
        viewModel.closeDetail()
    }
}
