using System.Text.Json.Nodes;
using ScreenAssistant.Core.Llm;
using ScreenAssistant.Core.Models;
using ScreenAssistant.Llm;
using ScreenAssistant.Llm.Adapters;

namespace ScreenAssistant.Tests;

public class AdapterTests
{
    private static readonly byte[] OnePixelPng = Convert.FromBase64String(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private static async Task<string> CollectAsync(IChatModelClient client, LlmChatRequest request)
    {
        var builder = new System.Text.StringBuilder();
        await foreach (var chunk in client.StreamAsync(request, CancellationToken.None))
        {
            builder.Append(chunk);
        }
        return builder.ToString();
    }

    private static LlmChatRequest Request(ModelConfig model, bool withImage = true) => new()
    {
        Model = model,
        SystemPrompt = "系统提示词",
        Messages = new[]
        {
            LlmMessage.User("这是什么？", withImage ? new[] { LlmImage.FromPng(OnePixelPng) } : Array.Empty<LlmImage>())
        }
    };

    [Fact]
    public async Task OpenAi_ParsesDeltaContent_AndRequestShape()
    {
        const string sse = """
            data: {"choices":[{"delta":{"content":"你好"}}]}

            data: {"choices":[{"delta":{"content":"，世界"}}]}

            data: [DONE]

            """;

        var handler = new CapturingHandler(sse);
        var model = new ModelConfig
        {
            Provider = LlmProvider.OpenAiCompatible,
            BaseUrl = "https://example.com/v1",
            ApiKey = "sk-test",
            ModelName = "gpt-test",
            MaxTokens = 512,
            Temperature = 0.3
        };

        var client = new OpenAiCompatibleClient(model, new StubHttpClientProvider(handler).Client);
        var text = await CollectAsync(client, Request(model));

        Assert.Equal("你好，世界", text);
        Assert.Equal("https://example.com/v1/chat/completions", handler.LastRequest!.RequestUri!.ToString());
        Assert.Equal("Bearer", handler.LastRequest.Headers.Authorization!.Scheme);
        Assert.Equal("sk-test", handler.LastRequest.Headers.Authorization.Parameter);

        var body = JsonNode.Parse(handler.LastBody!)!;
        Assert.Equal("gpt-test", body["model"]!.GetValue<string>());
        Assert.True(body["stream"]!.GetValue<bool>());
        Assert.Equal(512, body["max_tokens"]!.GetValue<int>());
        Assert.Equal("系统提示词", body["messages"]![0]!["content"]!.GetValue<string>());

        var userParts = body["messages"]![1]!["content"]!.AsArray();
        Assert.Equal("text", userParts[0]!["type"]!.GetValue<string>());
        Assert.StartsWith("data:image/png;base64,", userParts[1]!["image_url"]!["url"]!.GetValue<string>());
    }

    [Fact]
    public async Task Claude_ParsesContentBlockDelta_AndRequiresMaxTokens()
    {
        const string sse = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_1"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"第一段"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"第二段"}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}

            """;

        var handler = new CapturingHandler(sse);
        var model = new ModelConfig
        {
            Provider = LlmProvider.Claude,
            BaseUrl = "https://api.anthropic.com",
            ApiKey = "sk-ant",
            ModelName = "claude-test",
            MaxTokens = null
        };

        var client = new ClaudeClient(model, new StubHttpClientProvider(handler).Client);
        var text = await CollectAsync(client, Request(model));

        Assert.Equal("第一段第二段", text);
        Assert.Equal("https://api.anthropic.com/v1/messages", handler.LastRequest!.RequestUri!.ToString());
        Assert.Equal("sk-ant", Assert.Single(handler.LastRequest.Headers.GetValues("x-api-key")));
        Assert.Equal("2023-06-01", Assert.Single(handler.LastRequest.Headers.GetValues("anthropic-version")));

        var body = JsonNode.Parse(handler.LastBody!)!;
        // Claude 必须带 max_tokens，未配置时给默认值
        Assert.Equal(4096, body["max_tokens"]!.GetValue<int>());
        Assert.Equal("系统提示词", body["system"]!.GetValue<string>());

        var content = body["messages"]![0]!["content"]!.AsArray();
        Assert.Equal("image", content[0]!["type"]!.GetValue<string>());
        Assert.Equal("base64", content[0]!["source"]!["type"]!.GetValue<string>());
        Assert.Equal("image/png", content[0]!["source"]!["media_type"]!.GetValue<string>());
        Assert.Equal("text", content[1]!["type"]!.GetValue<string>());
    }

    [Fact]
    public async Task Gemini_ParsesCandidateParts_AndUsesInlineData()
    {
        const string sse = """
            data: {"candidates":[{"content":{"parts":[{"text":"甲"}]}}]}

            data: {"candidates":[{"content":{"parts":[{"text":"乙"}]}}]}

            """;

        var handler = new CapturingHandler(sse);
        var model = new ModelConfig
        {
            Provider = LlmProvider.Gemini,
            BaseUrl = "https://generativelanguage.googleapis.com",
            ApiKey = "goog-key",
            ModelName = "gemini-test"
        };

        var client = new GeminiClient(model, new StubHttpClientProvider(handler).Client);
        var text = await CollectAsync(client, Request(model));

        Assert.Equal("甲乙", text);
        Assert.Equal(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-test:streamGenerateContent?alt=sse",
            handler.LastRequest!.RequestUri!.ToString());
        Assert.Equal("goog-key", Assert.Single(handler.LastRequest.Headers.GetValues("x-goog-api-key")));

        var body = JsonNode.Parse(handler.LastBody!)!;
        Assert.Equal("系统提示词",
            body["systemInstruction"]!["parts"]![0]!["text"]!.GetValue<string>());
        var parts = body["contents"]![0]!["parts"]!.AsArray();
        Assert.Equal("这是什么？", parts[0]!["text"]!.GetValue<string>());
        Assert.Equal("image/png", parts[1]!["inline_data"]!["mime_type"]!.GetValue<string>());
    }

    [Fact]
    public async Task Gemini_AcceptsModelsPrefixInModelName()
    {
        var handler = new CapturingHandler("data: {\"candidates\":[]}\n");
        var model = new ModelConfig
        {
            Provider = LlmProvider.Gemini,
            ApiKey = "k",
            ModelName = "models/gemini-2.0-flash"
        };
        var client = new GeminiClient(model, new StubHttpClientProvider(handler).Client);
        await CollectAsync(client, Request(model, withImage: false));

        Assert.Contains("/v1beta/models/gemini-2.0-flash:streamGenerateContent", handler.LastRequest!.RequestUri!.ToString());
    }
}
