package com.regionx.app.core

import com.regionx.app.core.llm.DefaultChatModelClientFactory
import com.regionx.app.core.llm.HttpClientProvider
import com.regionx.app.core.llm.LlmChatRequest
import com.regionx.app.core.llm.LlmErrorKind
import com.regionx.app.core.llm.LlmException
import com.regionx.app.core.llm.LlmMessage
import com.regionx.app.core.llm.ModelCatalog
import com.regionx.app.core.llm.OpenAiCompatibleClient
import com.regionx.app.core.model.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Real sockets verify cancellation during both response headers and response-body reads. */
class NetworkLifecycleTest {
    private lateinit var server: MockWebServer

    @Before fun start() {
        server = MockWebServer()
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun config() = ModelConfig(baseUrl = server.url("/v1").toString(), modelName = "test")

    private fun stream() = config().let { model ->
        DefaultChatModelClientFactory(HttpClientProvider()).create(model)
            .stream(LlmChatRequest(model, listOf(LlmMessage.User("hi"))))
    }

    private fun delayedStream(headers: Boolean = false): MockResponse {
        val response = MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: [DONE]\n\n")
        return if (headers) response.setHeadersDelay(1600, TimeUnit.MILLISECONDS)
        else response.setBodyDelay(1600, TimeUnit.MILLISECONDS)
    }

    private suspend fun awaitRequest() {
        withContext(Dispatchers.IO) {
            assertNotNull("HTTP request did not reach the server", server.takeRequest(3, TimeUnit.SECONDS))
        }
        // Let OkHttp enter the pending header/body read; the server remains bounded for RED runs.
        delay(100)
    }

    @Test fun cancellationMustInterruptBlockedHttpRead() = runBlocking {
        server.enqueue(delayedStream())
        val job = launch(Dispatchers.Default) { stream().collect() }
        awaitRequest()
        val start = System.nanoTime()
        job.cancelAndJoin()
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue("Cancellation waited for response body: ${elapsed}ms", elapsed < 500)
    }

    @Test fun cancellationMustInterruptWaitingForResponseHeaders() = runBlocking {
        server.enqueue(delayedStream(headers = true))
        val job = launch(Dispatchers.Default) { stream().collect() }
        awaitRequest()
        val start = System.nanoTime()
        job.cancelAndJoin()
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue("Cancellation waited for response headers: ${elapsed}ms", elapsed < 500)
    }

    @Test fun businessTimeoutMustInterruptSilentStream() = runBlocking {
        server.enqueue(delayedStream())
        val pending = async(Dispatchers.Default) {
            val start = System.nanoTime()
            val error = runCatching { withTimeout(250) { stream().collect() } }.exceptionOrNull()
            error to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        }
        val (error, elapsed) = pending.await()
        assertTrue("Expected coroutine timeout, got $error", error is TimeoutCancellationException)
        assertTrue("Business timeout waited for response body: ${elapsed}ms", elapsed < 900)
    }

    @Test fun catalogCancellationMustInterruptBlockedBodyRead() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"data\":[{\"id\":\"test\"}]}")
            .setBodyDelay(1600, TimeUnit.MILLISECONDS))
        val job = launch(Dispatchers.Default) { ModelCatalog(HttpClientProvider()).listModels(config()) }
        awaitRequest()
        val start = System.nanoTime()
        job.cancelAndJoin()
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue("Catalog cancellation waited for response body: ${elapsed}ms", elapsed < 500)
    }

    @Test fun catalogBusinessTimeoutMustInterruptWaitingForHeaders() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"data\":[]}")
            .setHeadersDelay(1600, TimeUnit.MILLISECONDS))
        val pending = async(Dispatchers.Default) {
            val start = System.nanoTime()
            val error = runCatching {
                withTimeout(250) { ModelCatalog(HttpClientProvider()).listModels(config()) }
            }.exceptionOrNull()
            error to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        }
        val (error, elapsed) = pending.await()
        assertTrue("Expected coroutine timeout, got $error", error is TimeoutCancellationException)
        assertTrue("Catalog timeout waited for response headers: ${elapsed}ms", elapsed < 900)
    }

    @Test fun httpCallTimeoutMustBeClassifiedAsTimeout() = runBlocking {
        server.enqueue(delayedStream(headers = true))
        val model = config()
        val http = OkHttpClient.Builder().callTimeout(150, TimeUnit.MILLISECONDS).build()
        val error = runCatching {
            OpenAiCompatibleClient(model, http)
                .stream(LlmChatRequest(model, listOf(LlmMessage.User("hi")))).collect()
        }.exceptionOrNull()
        assertTrue("Expected LlmException, got $error", error is LlmException)
        assertEquals(LlmErrorKind.Timeout, (error as LlmException).kind)
    }
}
