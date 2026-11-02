using System.Net.Http.Headers;
using System.Text.Json.Nodes;
using ScreenAssistant.Core.Llm;
using ScreenAssistant.Core.Models;

namespace ScreenAssistant.Llm.Adapters;

/// <summary>OpenAI 兼容接口：{baseUrl}/chat/completions，流式解析 choices[0].delta.content。</summary>
public sealed class OpenAiCompatibleClient : ChatModelClientBase
{
    public OpenAiCompatibleClient(ModelConfig config, HttpClient http) : base(config, http) { }

    protected override void ApplyAuth(HttpRequestMessage message)
    {
        if (!string.IsNullOrWhiteSpace(Config.ApiKey))
        {
            message.Headers.Authorization = new AuthenticationHeaderValue("Bearer", Config.ApiKey.Trim());
        }
    }

    public override async IAsyncEnumerable<string> StreamAsync(
        LlmChatRequest request,
        [System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken cancellationToken)
    {
        var baseUrl = NormalizeBase(Config.BaseUrl, ModelConfig.DefaultOpenAiBaseUrl);
        var endpoint = new Uri($"{baseUrl}/chat/completions");

        var messages = new JsonArray();
        if (!string.IsNullOrWhiteSpace(request.SystemPrompt))
        {
            messages.Add(new JsonObject { ["role"] = "system", ["content"] = request.SystemPrompt });
        }

        foreach (var message in request.Messages)
        {
            messages.Add(BuildMessage(message));
        }

        var payload = new JsonObject
        {
            ["model"] = Config.ModelName,
            ["messages"] = messages,
            ["stream"] = true
        };
        if (Config.Temperature is { } temperature) payload["temperature"] = temperature;
        if (Config.MaxTokens is { } maxTokens) payload["max_tokens"] = maxTokens;

        using var httpRequest = CreateRequest(endpoint, payload);
        using var response = await Http.SendAsync(httpRequest, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
            .ConfigureAwait(false);
        await using var stream = await LlmHttp.EnsureSuccessAsync(response, cancellationToken).ConfigureAwait(false);

        await foreach (var data in LlmHttp.ReadSseDataAsync(stream, cancellationToken).ConfigureAwait(false))
        {
            if (data.Length == 0) continue;
            if (data == "[DONE]") yield break;

            var node = Json.Parse(data);
            if (node is null) continue;

            if (node is JsonObject obj && obj.TryGetPropertyValue("error", out var error) && error is not null)
            {
                throw Protocol($"接口返回错误：{LlmHttp.Truncate(error.ToJsonString(), 400)}");
            }

            var text = Json.StringAt(node, "choices", 0, "delta", "content");
            if (!string.IsNullOrEmpty(text))
            {
                yield return text;
            }

            var finish = Json.StringAt(node, "choices", 0, "finish_reason");
            if (finish == "stop" || finish == "length" || finish == "content_filter")
            {
                yield break;
            }
        }
    }

    private static JsonObject BuildMessage(LlmMessage message)
    {
        var role = message.Role == ChatRole.Assistant ? "assistant" : "user";

        if (message.Images.Count == 0)
        {
            return new JsonObject { ["role"] = role, ["content"] = message.Text };
        }

        // 多模态：content 为数组，先文本再图片
        var parts = new JsonArray();
        if (!string.IsNullOrWhiteSpace(message.Text))
        {
            parts.Add(new JsonObject { ["type"] = "text", ["text"] = message.Text });
        }
        foreach (var image in message.Images)
        {
            parts.Add(new JsonObject
            {
                ["type"] = "image_url",
                ["image_url"] = new JsonObject { ["url"] = DataUrl(image) }
            });
        }
        return new JsonObject { ["role"] = role, ["content"] = parts };
    }
}
