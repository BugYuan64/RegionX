package com.regionx.app.core.tasks

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.regionx.app.core.llm.ChatModelClient
import com.regionx.app.core.llm.ChatModelClientFactory
import com.regionx.app.core.llm.DefaultChatModelClientFactory
import com.regionx.app.core.llm.HttpClientProvider
import com.regionx.app.core.llm.LlmChatRequest
import com.regionx.app.core.model.AppSettings
import com.regionx.app.core.model.LlmProvider
import com.regionx.app.core.model.ModelConfig
import com.regionx.app.core.model.TaskRecord
import com.regionx.app.core.model.TaskStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Verifies the actual TaskRunner -> provider payload, including independent tasks in one session. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class TaskImageRequestTest {
    private class Store : TaskPersistence {
        val tasks = mutableMapOf<String, TaskRecord>()
        val replies = mutableMapOf<String, String>()
        override suspend fun updateTask(task: TaskRecord) { tasks[task.id] = task }
        override suspend fun saveAssistantMessage(task: TaskRecord, content: String) { replies[task.id] = content }
    }

    private class CountingFactory : ChatModelClientFactory {
        var requests = 0
        override fun create(config: ModelConfig): ChatModelClient {
            requests++
            return object : ChatModelClient {
                override fun stream(request: LlmChatRequest): Flow<String> = flowOf("answer without a picture")
            }
        }
    }

    private fun runner(store: Store, factory: ChatModelClientFactory, source: TaskImageSource) =
        TaskRunner(factory, store, source, { AppSettings() }, {}, {})

    @Test fun missingOrUnreadableScreenshotFailsBeforeCreatingAClient() = runBlocking {
        val sources = listOf(
            TaskImageSource { null },
            TaskImageSource { byteArrayOf() },
            TaskImageSource { throw IOException("cannot read screenshot") },
        )
        for (source in sources) {
            val store = Store()
            val factory = CountingFactory()
            val task = TaskRecord(id = "missing", imagePath = "missing.png")
            runner(store, factory, source).run(task, ModelConfig())
            assertEquals(0, factory.requests)
            assertEquals(TaskStatus.Failed, store.tasks[task.id]?.status)
            assertTrue(store.tasks[task.id]?.error?.contains("截图") == true)
            assertTrue(store.replies.isEmpty())
        }
    }

    @Test fun imageTaskWithTextOnlyModelFailsBeforeCreatingAClient() = runBlocking {
        val store = Store()
        val factory = CountingFactory()
        val task = TaskRecord(id = "no-vision", imagePath = "question.png")
        runner(store, factory, TaskImageSource { png(0xff123456.toInt()) })
            .run(task, ModelConfig(supportsImages = false))
        assertEquals(0, factory.requests)
        assertEquals(TaskStatus.Failed, store.tasks[task.id]?.status)
        assertTrue(store.tasks[task.id]?.error?.contains("图片") == true)
    }

    @Test fun cancellationWhileReadingScreenshotCannotBecomeATextOnlyRequest() = runBlocking {
        val store = Store()
        val factory = CountingFactory()
        val task = TaskRecord(id = "cancelled", imagePath = "question.png")
        runner(store, factory, TaskImageSource { throw CancellationException("stop") }).run(task, ModelConfig())
        assertEquals(0, factory.requests)
        assertEquals(TaskStatus.Cancelled, store.tasks[task.id]?.status)
    }

    @Test fun consecutiveOpenAiTasksSendTheirOwnDecodablePng() = consecutiveTasks(LlmProvider.OpenAiCompatible)

    @Test fun consecutiveGeminiTasksSendTheirOwnDecodablePng() = consecutiveTasks(LlmProvider.Gemini)

    private fun consecutiveTasks(provider: LlmProvider) = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val pixels = listOf(0xff123456.toInt(), 0xffa1b2c3.toInt())
            val screenshots = pixels.map(::png)
            val tasks = screenshots.indices.map { TaskRecord(id = "question-$it", sessionId = "same-session", imagePath = "$it.png") }
            val store = Store()
            val factory = DefaultChatModelClientFactory(HttpClientProvider())
            val taskRunner = runner(store, factory, TaskImageSource { screenshots[tasks.indexOfFirst { candidate -> candidate.id == it.id }] })
            val config = ModelConfig(provider = provider, baseUrl = server.url("/").toString().trimEnd('/'), modelName = "vision")
            tasks.forEachIndexed { index, task ->
                val event = if (provider == LlmProvider.Gemini)
                    "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}\n\n"
                else "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n"
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(event))
                taskRunner.run(task, config)
                assertEquals(TaskStatus.Completed, store.tasks[task.id]?.status)
                val request = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val encoded = if (provider == LlmProvider.Gemini) {
                    val contents = body.getValue("contents").jsonArray
                    assertEquals("Each task must be independent", 1, contents.size)
                    val parts = contents.single().jsonObject.getValue("parts").jsonArray
                    val image = parts.map { it.jsonObject }.single { it.containsKey("inline_data") }.getValue("inline_data").jsonObject
                    assertEquals("image/png", image.getValue("mime_type").jsonPrimitive.content)
                    image.getValue("data").jsonPrimitive.content
                } else {
                    val users = body.getValue("messages").jsonArray.map { it.jsonObject }.filter { it["role"]?.jsonPrimitive?.content == "user" }
                    assertEquals("Each task must be independent", 1, users.size)
                    val image = users.single().getValue("content").jsonArray.map { it.jsonObject }.single { it["type"]?.jsonPrimitive?.content == "image_url" }
                    val url = image.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content
                    assertTrue(url.startsWith("data:image/png;base64,"))
                    url.substringAfter(',')
                }
                val attached = Base64.getDecoder().decode(encoded)
                assertArrayEquals("Task ${task.id} must attach its own screenshot bytes", screenshots[index], attached)
                val image = checkNotNull(BitmapFactory.decodeByteArray(attached, 0, attached.size))
                assertEquals(2, image.width)
                assertEquals(2, image.height)
                assertEquals("The payload must contain the selected pixels", pixels[index], image.getPixel(0, 0))
                image.recycle()
            }
        } finally { server.shutdown() }
    }

    private fun png(color: Int): ByteArray {
        val image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        image.setPixel(0, 0, color)
        image.setPixel(1, 1, 0xffffffff.toInt())
        return try {
            ByteArrayOutputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        } finally { image.recycle() }
    }
}
