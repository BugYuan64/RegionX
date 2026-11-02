using System.Text.Json.Nodes;
using ScreenAssistant.Core.Llm;
using ScreenAssistant.Core.Models;

namespace ScreenAssistant.Llm.Adapters;

/// <summary>
/// Claude Messages API：POST {baseUrl}/v1/messages。
/// 流式解析 content_block_delta 事件的 delta.text。必须携带 max_tokens。
/// </summary>
public sealed class ClaudeClient : ChatModelClientBase
{
    private const string ApiVersion = "2023-06-01";

    public ClaudeClient(ModelConfig config, HttpClient http) : base(config, http) { }

    protected override void ApplyAuth(HttpRequestMessage message)
    {
        message.Headers.TryAddWithoutValidation("x-api-key", Config.ApiKey.Trim());
        message.Headers.TryAddWithoutValidation("anthropic-version", ApiVersion);
    }

    public override async IAsyncEnumerable<string> StreamAsync(
        LlmChatRequest request,
        [System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken cancellationToken)
    {
        var baseUrl = NormalizeBase(Config.BaseUrl, ModelConfig.DefaultClaudeBaseUrl);
        var endpoint = new Uri(baseUrl.EndsWith("/v1", StringComparison.OrdinalIgnoreCase)
            ? $"{baseUrl}/messages"
            : $"{baseUrl}/v1/messages");

        var messages = new JsonArray();
        foreach (var message in request.Messages)
        {
            messages.Add(BuildMessage(message));
        }

        var payload = new JsonObject
        {
            ["model"] = Config.ModelName,
            ["messages"] = messages,
            ["max_tokens"] = Config.MaxTokens ?? 4096,
            ["stream"] = true
        };
        if (!string.IsNullOrWhiteSpace(request.SystemPrompt))
        {
            payload["system"] = request.SystemPrompt;
        }
        if (Config.Temperature is { } temperature)
        {
            payload["temperature"] = temperature;
        }

        using var httpRequest = CreateRequest(endpoint, payload);
        using var response = await Http.SendAsync(httpRequest, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
            .ConfigureAwait(false);
        await using var stream = await LlmHttp.EnsureSuccessAsync(response, cancellationToken).ConfigureAwait(false);

        await foreach (var data in LlmHttp.ReadSseDataAsync(stream, cancellationToken).ConfigureAwait(false))
        {
            if (data.Length == 0) continue;

            var node = Json.Parse(data);
            if (node is null) continue;

            var type = Json.StringAt(node, "type");
            switch (type)
            {
                case "content_block_delta":
                    // 文本增量
                    var text = Json.StringAt(node, "delta", "text");
                    if (string.IsNullOrEmpty(text))
                    {
                        // 部分兼容网关把增量放在 delta.content 里
                        text = Json.StringAt(node, "delta", "content");
                    }
                    if (!string.IsNullOrEmpty(text)) yield return text;
                    break;

                case "message_delta":
                    var stopReason = Json.StringAt(node, "delta", "stop_reason");
                    if (stopReason is not null) yield break;
                    break;

                case "message_stop":
                    yield break;

                case "error":
                    throw Protocol($"接口返回错误：{LlmHttp.Truncate(data, 400)}");
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

        var parts = new JsonArray();
        foreach (var image in message.Images)
        {
            parts.Add(new JsonObject
            {
                ["type"] = "image",
                ["source"] = new JsonObject
                {
                    ["type"] = "base64",
                    ["media_type"] = image.MimeType,
                    ["data"] = Convert.ToBase64String(image.Data)
                }
            });
        }
        if (!string.IsNullOrWhiteSpace(message.Text))
        {
            parts.Add(new JsonObject { ["type"] = "text", ["text"] = message.Text });
        }
        return new JsonObject { ["role"] = role, ["content"] = parts };
    }
}
