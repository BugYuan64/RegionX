using System.Text.Json.Nodes;
using ScreenAssistant.Core.Llm;
using ScreenAssistant.Core.Models;

namespace ScreenAssistant.Llm.Adapters;

/// <summary>
/// Gemini API：POST {baseUrl}/v1beta/models/{model}:streamGenerateContent?alt=sse。
/// 流式解析 candidates[0].content.parts[*].text。
/// </summary>
public sealed class GeminiClient : ChatModelClientBase
{
    public GeminiClient(ModelConfig config, HttpClient http) : base(config, http) { }

    protected override void ApplyAuth(HttpRequestMessage message)
    {
        message.Headers.TryAddWithoutValidation("x-goog-api-key", Config.ApiKey.Trim());
    }

    public override async IAsyncEnumerable<string> StreamAsync(
        LlmChatRequest request,
        [System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken cancellationToken)
    {
        var baseUrl = NormalizeBase(Config.BaseUrl, ModelConfig.DefaultGeminiBaseUrl);
        // 允许用户填 .../v1beta 或完整根地址
        var apiRoot = baseUrl.EndsWith("/v1beta", StringComparison.OrdinalIgnoreCase)
            || baseUrl.EndsWith("/v1", StringComparison.OrdinalIgnoreCase)
            ? baseUrl
            : $"{baseUrl}/v1beta";

        var model = Config.ModelName.Trim();
        if (model.StartsWith("models/", StringComparison.OrdinalIgnoreCase))
        {
            model = model["models/".Length..];
        }
        var endpoint = new Uri($"{apiRoot}/models/{Uri.EscapeDataString(model)}:streamGenerateContent?alt=sse");

        var contents = new JsonArray();
        foreach (var message in request.Messages)
        {
            contents.Add(BuildContent(message));
        }

        var payload = new JsonObject { ["contents"] = contents };
        if (!string.IsNullOrWhiteSpace(request.SystemPrompt))
        {
            payload["systemInstruction"] = new JsonObject
            {
                ["parts"] = new JsonArray(new JsonObject { ["text"] = request.SystemPrompt })
            };
        }

        var generationConfig = new JsonObject();
        if (Config.Temperature is { } temperature) generationConfig["temperature"] = temperature;
        if (Config.MaxTokens is { } maxTokens) generationConfig["maxOutputTokens"] = maxTokens;
        if (generationConfig.Count > 0) payload["generationConfig"] = generationConfig;

        using var httpRequest = CreateRequest(endpoint, payload);
        using var response = await Http.SendAsync(httpRequest, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
            .ConfigureAwait(false);
        await using var stream = await LlmHttp.EnsureSuccessAsync(response, cancellationToken).ConfigureAwait(false);

        await foreach (var data in LlmHttp.ReadSseDataAsync(stream, cancellationToken).ConfigureAwait(false))
        {
            if (data.Length == 0 || data == "[DONE]") continue;

            var node = Json.Parse(data);
            if (node is null) continue;

            if (node is JsonObject obj && obj.TryGetPropertyValue("error", out var error) && error is not null)
            {
                throw Protocol($"接口返回错误：{LlmHttp.Truncate(error.ToJsonString(), 400)}");
            }

            var parts = Json.At(node, "candidates", 0, "content", "parts");
            if (parts is not JsonArray partArray) continue;

            foreach (var part in partArray)
            {
                var text = Json.StringAt(part, "text");
                if (!string.IsNullOrEmpty(text)) yield return text;
            }
        }
    }

    private static JsonObject BuildContent(LlmMessage message)
    {
        // Gemini 使用 "model" 表示助手角色
        var role = message.Role == ChatRole.Assistant ? "model" : "user";
        var parts = new JsonArray();

        if (!string.IsNullOrWhiteSpace(message.Text))
        {
            parts.Add(new JsonObject { ["text"] = message.Text });
        }
        foreach (var image in message.Images)
        {
            parts.Add(new JsonObject
            {
                ["inline_data"] = new JsonObject
                {
                    ["mime_type"] = image.MimeType,
                    ["data"] = Convert.ToBase64String(image.Data)
                }
            });
        }

        return new JsonObject { ["role"] = role, ["parts"] = parts };
    }
}
