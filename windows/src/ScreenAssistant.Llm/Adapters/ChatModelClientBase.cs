using System.Net.Http.Headers;
using System.Text;
using ScreenAssistant.Core.Llm;
using ScreenAssistant.Core.Models;

namespace ScreenAssistant.Llm.Adapters;

public abstract class ChatModelClientBase : IChatModelClient
{
    protected readonly ModelConfig Config;
    protected readonly HttpClient Http;

    protected ChatModelClientBase(ModelConfig config, HttpClient http)
    {
        Config = config;
        Http = http;
    }

    public abstract IAsyncEnumerable<string> StreamAsync(LlmChatRequest request, CancellationToken cancellationToken);

    protected HttpRequestMessage CreateRequest(Uri endpoint, object payload)
    {
        var message = new HttpRequestMessage(HttpMethod.Post, endpoint)
        {
            Content = new StringContent(Json.Serialize(payload), Encoding.UTF8, "application/json")
        };
        message.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("text/event-stream"));
        ApplyAuth(message);
        ApplyExtraHeaders(message);
        return message;
    }

    protected abstract void ApplyAuth(HttpRequestMessage message);

    private void ApplyExtraHeaders(HttpRequestMessage message)
    {
        if (string.IsNullOrWhiteSpace(Config.ExtraHeaders)) return;
        foreach (var rawLine in Config.ExtraHeaders.Split('\n'))
        {
            var line = rawLine.Trim();
            if (line.Length == 0) continue;
            var idx = line.IndexOf(':');
            if (idx <= 0) continue;
            var name = line[..idx].Trim();
            var value = line[(idx + 1)..].Trim();
            if (name.Length == 0) continue;
            // 认证头由适配器负责，避免被覆盖
            if (name.Equals("Authorization", StringComparison.OrdinalIgnoreCase) ||
                name.Equals("x-api-key", StringComparison.OrdinalIgnoreCase) ||
                name.Equals("x-goog-api-key", StringComparison.OrdinalIgnoreCase) ||
                name.Equals("Content-Type", StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }
            message.Headers.TryAddWithoutValidation(name, value);
        }
    }

    protected static string DataUrl(LlmImage image) =>
        $"data:{image.MimeType};base64,{Convert.ToBase64String(image.Data)}";

    protected static LlmException Protocol(string detail) => new(LlmErrorKind.Protocol, detail);

    protected static string NormalizeBase(string baseUrl, string fallback)
    {
        var value = string.IsNullOrWhiteSpace(baseUrl) ? fallback : baseUrl.Trim();
        return value.TrimEnd('/');
    }
}
