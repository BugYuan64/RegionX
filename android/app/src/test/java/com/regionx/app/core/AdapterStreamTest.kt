package com.regionx.app.core

import com.regionx.app.core.llm.DefaultChatModelClientFactory
import com.regionx.app.core.llm.HttpClientProvider
import com.regionx.app.core.llm.LlmChatRequest
import com.regionx.app.core.llm.LlmErrorKind
import com.regionx.app.core.llm.LlmException
import com.regionx.app.core.llm.LlmImage
import com.regionx.app.core.llm.LlmMessage
import com.regionx.app.core.model.LlmProvider
import com.regionx.app.core.model.ModelConfig
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 三个适配器的流式解析：用 MockWebServer 回放各厂商的 SSE 格式，
 * 验证端点、认证头、增量文本与错误映射（对应技术文档 8「先单独验证三个适配器」）。
 */
class AdapterStreamTest {

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    private val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body)
        )
    }

    private fun collect(
        config: ModelConfig,
        messages: List<LlmMessage> = listOf(LlmMessage.User("hi")),
    ): List<String> = runBlocking {
        val client = DefaultChatModelClientFactory(HttpClientProvider()).create(config)
        client.stream(LlmChatRequest(config, messages, "系统提示")).toList()
    }

    @Test
    fun `openai compatible parses delta content and stops on done`() {
        enqueue(
            """
            data: {"choices":[{"delta":{"content":"你好"},"finish_reason":null}]}

            data: {"choices":[{"delta":{"content":"，世界"},"finish_reason":null}]}

            data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

            """.trimIndent() + "\n\n"
        )

        val config = ModelConfig(
            name = "测试",
            provider = LlmProvider.OpenAiCompatible,
            baseUrl = baseUrl,
            apiKey = "sk-test",
            modelName = "gpt-4o-mini",
        )
        val chunks = collect(config)

        assertEquals(listOf("你好", "，世界"), chunks)
        val request = server.takeRequest()
        assertEquals("/chat/completions", request.path)
        assertEquals("Bearer sk-test", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"system\""))
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"model\":\"gpt-4o-mini\""))
    }

    @Test
    fun `openai compatible sends images as data url parts`() {
        enqueue("data: [DONE]\n\n")
        val config = ModelConfig(
            provider = LlmProvider.OpenAiCompatible,
            baseUrl = baseUrl,
            modelName = "vision",
        )
        collect(config, listOf(LlmMessage.User("看图", listOf(LlmImage(byteArrayOf(1, 2, 3))))))

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"type\":\"image_url\""))
        assertTrue(body.contains("data:image/png;base64,"))
    }

    @Test
    fun finalChunkContentMustNotBeDiscarded() {
        for (reason in listOf("stop", "length", "content_filter")) {
            enqueue("data: {\"choices\":[{\"delta\":{\"content\":\"last\"},\"finish_reason\":\"$reason\"}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"after end\"}}]}\n\n")
            val config = ModelConfig(baseUrl = baseUrl, modelName = "test")
            assertEquals("Final content must precede $reason termination", listOf("last"), collect(config))
        }
    }

    @Test
    fun `claude parses content block deltas and requires max tokens`() {
        enqueue(
            """
            data: {"type":"message_start"}

            data: {"type":"content_block_delta","delta":{"text":"第一段"}}

            data: {"type":"content_block_delta","delta":{"text":"第二段"}}

            data: {"type":"message_stop"}

            """.trimIndent() + "\n\n"
        )

        val config = ModelConfig(
            provider = LlmProvider.Claude,
            baseUrl = baseUrl,
            apiKey = "claude-key",
            modelName = "claude-sonnet-5-5",
        )
        val chunks = collect(config)

        assertEquals(listOf("第一段", "第二段"), chunks)
        val request = server.takeRequest()
        assertEquals("/v1/messages", request.path)
        assertEquals("claude-key", request.getHeader("x-api-key"))
        assertEquals("2023-06-01", request.getHeader("anthropic-version"))
        assertTrue(request.body.readUtf8().contains("\"max_tokens\""))
    }

    @Test
    fun `gemini parses candidate parts`() {
        enqueue(
            """
            data: {"candidates":[{"content":{"parts":[{"text":"Gemini"}]}}]}

            data: {"candidates":[{"content":{"parts":[{"text":" 回答"}]}}]}

            """.trimIndent() + "\n\n"
        )

        val config = ModelConfig(
            provider = LlmProvider.Gemini,
            baseUrl = baseUrl,
            apiKey = "gemini-key",
            modelName = "gemini-3-pro",
        )
        val chunks = collect(config)

        assertEquals(listOf("Gemini", " 回答"), chunks)
        val request = server.takeRequest()
        assertEquals("/v1beta/models/gemini-3-pro:streamGenerateContent?alt=sse", request.path)
        assertEquals("gemini-key", request.getHeader("x-goog-api-key"))
    }

    @Test
    fun `http 401 maps to authentication error`() {
        enqueue("{\"error\":{\"message\":\"bad key\"}}", code = 401)
        val config = ModelConfig(
            provider = LlmProvider.OpenAiCompatible,
            baseUrl = baseUrl,
            modelName = "x",
        )
        val error = runCatching { collect(config) }.exceptionOrNull()
        assertTrue("期望 LlmException，实际 $error", error is LlmException)
        assertEquals(LlmErrorKind.Authentication, (error as LlmException).kind)
    }

    @Test
    fun `stream error event surfaces as protocol error`() {
        enqueue("data: {\"error\":{\"message\":\"quota\"}}\n\n")
        val config = ModelConfig(
            provider = LlmProvider.OpenAiCompatible,
            baseUrl = baseUrl,
            modelName = "x",
        )
        val error = runCatching { collect(config) }.exceptionOrNull()
        assertTrue("期望 LlmException，实际 $error", error is LlmException)
        assertEquals(LlmErrorKind.Protocol, (error as LlmException).kind)
    }

    @Test
    fun `extra headers are applied but auth headers are not overridden`() {
        enqueue("data: [DONE]\n\n")
        val config = ModelConfig(
            provider = LlmProvider.OpenAiCompatible,
            baseUrl = baseUrl,
            apiKey = "real-key",
            modelName = "x",
            extraHeaders = "X-Gateway: abc\nAuthorization: should-not-win",
        )
        collect(config)
        val request = server.takeRequest()
        assertEquals("abc", request.getHeader("X-Gateway"))
        assertEquals("Bearer real-key", request.getHeader("Authorization"))
    }

    @Test
    fun `no api key means no authorization header`() {
        enqueue("data: [DONE]\n\n")
        val config = ModelConfig(
            provider = LlmProvider.OpenAiCompatible,
            baseUrl = baseUrl,
            modelName = "x",
        )
        collect(config)
        assertEquals(null, server.takeRequest().getHeader("Authorization"))
    }
}
