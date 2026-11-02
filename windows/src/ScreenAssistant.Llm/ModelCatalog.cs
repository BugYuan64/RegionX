using System.Net;
using System.Net.Http.Headers;
using System.Text.Json.Nodes;
using ScreenAssistant.Core.Llm;
using ScreenAssistant.Core.Models;

namespace ScreenAssistant.Llm;

/// <summary>
/// 读取上游模型列表：
/// OpenAI 兼容 → GET {baseUrl}/models；Claude → GET {baseUrl}/v1/models；Gemini → GET {baseUrl}/v1beta/models。
/// baseUrl 允许只填主机名（如 https://api.example.com），会按接口类型自动补路径。
/// </summary>
public sealed class ModelCatalog : IModelCatalog
{
    private readonly IHttpClientProvider _http;

    public ModelCatalog(IHttpClientProvider http) => _http = http;

    public async Task<IReadOnlyList<UpstreamModel>> ListModelsAsync(
        ModelConfig config,
        CancellationToken cancellationToken)
    {
        var urls = BuildUrls(config);

        // 依次尝试候选地址：用户只填主机名、或把完整端点粘进来时都能取到列表。
        // 只有 404 / 405 才换下一个候选；认证、限流等错误立即抛出。
        LlmException? last = null;
        foreach (var url in urls)
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, url);
            request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
            ApplyAuth(request, config);
            ApplyExtraHeaders(request, config);

            using var response = await _http.Client
                .SendAsync(request, HttpCompletionOption.ResponseContentRead, cancellationToken)
                .ConfigureAwait(false);

            if (response.IsSuccessStatusCode)
            {
                var okJson = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
                var okNode = Json.Parse(okJson);
                if (okNode is null)
                {
                    throw new LlmException(LlmErrorKind.Protocol, "模型列表返回的不是合法 JSON。");
                }
                return Parse(config.Provider, okNode)
                    .DistinctBy(m => m.Id, StringComparer.Ordinal)
                    .OrderBy(m => m.Id, StringComparer.OrdinalIgnoreCase)
                    .ThenBy(m => m.Id, StringComparer.Ordinal)
                    .ToList();
            }

            var body = "";
            try
            {
                body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
            }
            catch
            {
                // 忽略读取错误正文时的异常
            }

            last = LlmHttp.MapStatus(response.StatusCode, body);
            if (response.StatusCode is not (HttpStatusCode.NotFound or HttpStatusCode.MethodNotAllowed))
            {
                throw last;
            }
        }

        throw last ?? new LlmException(LlmErrorKind.Unknown, "无法访问模型列表接口。");
    }

    private static IReadOnlyList<UpstreamModel> Parse(LlmProvider provider, JsonNode node) => provider switch
    {
        LlmProvider.Claude => ParseClaude(node),
        LlmProvider.Gemini => ParseGemini(node),
        _ => ParseOpenAi(node)
    };

    /// <summary>候选模型列表地址（按优先级排序，去重）。</summary>
    private static IReadOnlyList<Uri> BuildUrls(ModelConfig config)
    {
        var raw = (string.IsNullOrWhiteSpace(config.BaseUrl) ? Fallback(config.Provider) : config.BaseUrl.Trim())
            .TrimEnd('/');

        // 用户可能直接粘贴了完整端点，先把这些后缀剥掉
        foreach (var suffix in new[] { "/chat/completions", "/completions", "/messages", "/models" })
        {
            if (raw.EndsWith(suffix, StringComparison.OrdinalIgnoreCase))
            {
                raw = raw[..^suffix.Length].TrimEnd('/');
                break;
            }
        }

        var urls = new List<string>();
        switch (config.Provider)
        {
            case LlmProvider.Claude:
                urls.Add(raw.EndsWith("/v1", StringComparison.OrdinalIgnoreCase) ? $"{raw}/models" : $"{raw}/v1/models");
                urls.Add($"{raw}/models");
                break;
            case LlmProvider.Gemini:
            {
                var root = raw.EndsWith("/v1beta", StringComparison.OrdinalIgnoreCase)
                           || raw.EndsWith("/v1", StringComparison.OrdinalIgnoreCase)
                    ? raw
                    : $"{raw}/v1beta";
                urls.Add($"{root}/models");
                urls.Add($"{raw}/models");
                break;
            }
            default:
                // 常见的 OpenAI 兼容网关：/v1/models；也有 /models 与 /openai/v1/models
                urls.Add(raw.EndsWith("/v1", StringComparison.OrdinalIgnoreCase) ? $"{raw}/models" : $"{raw}/v1/models");
                urls.Add($"{raw}/models");
                urls.Add($"{raw}/openai/v1/models");
                break;
        }

        return urls.Distinct(StringComparer.OrdinalIgnoreCase)
            .Select(u => Uri.TryCreate(u, UriKind.Absolute, out var uri) ? uri : null)
            .Where(u => u is not null)
            .Select(u => u!)
            .ToList();
    }

    private static string Fallback(LlmProvider provider) => provider switch
    {
        LlmProvider.Claude => ModelConfig.DefaultClaudeBaseUrl,
        LlmProvider.Gemini => ModelConfig.DefaultGeminiBaseUrl,
        _ => ModelConfig.DefaultOpenAiBaseUrl
    };

    /// <summary>附加请求头（与对话请求一致），跳过认证头避免覆盖。</summary>
    private static void ApplyExtraHeaders(HttpRequestMessage request, ModelConfig config)
    {
        if (string.IsNullOrWhiteSpace(config.ExtraHeaders)) return;
        foreach (var rawLine in config.ExtraHeaders.Split('\n'))
        {
            var line = rawLine.Trim();
            if (line.Length == 0) continue;
            var idx = line.IndexOf(':');
            if (idx <= 0) continue;
            var name = line[..idx].Trim();
            var value = line[(idx + 1)..].Trim();
            if (name.Length == 0) continue;
            if (name.Equals("Authorization", StringComparison.OrdinalIgnoreCase) ||
                name.Equals("x-api-key", StringComparison.OrdinalIgnoreCase) ||
                name.Equals("x-goog-api-key", StringComparison.OrdinalIgnoreCase) ||
                name.Equals("Content-Type", StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }
            request.Headers.TryAddWithoutValidation(name, value);
        }
    }

    private static void ApplyAuth(HttpRequestMessage request, ModelConfig config)
    {
        var key = config.ApiKey?.Trim() ?? "";
        if (key.Length == 0) return;

        switch (config.Provider)
        {
            case LlmProvider.Claude:
                request.Headers.TryAddWithoutValidation("x-api-key", key);
                request.Headers.TryAddWithoutValidation("anthropic-version", "2023-06-01");
                break;
            case LlmProvider.Gemini:
                request.Headers.TryAddWithoutValidation("x-goog-api-key", key);
                break;
            default:
                request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", key);
                break;
        }
    }

    /// <summary>OpenAI 兼容：{ data: [ { id } ] }，部分网关直接用数组。</summary>
    private static IReadOnlyList<UpstreamModel> ParseOpenAi(JsonNode node)
    {
        var items = node as JsonArray ?? Json.At(node, "data") as JsonArray;
        if (items is null) return Array.Empty<UpstreamModel>();

        var result = new List<UpstreamModel>();
        foreach (var item in items)
        {
            var id = Json.StringAt(item, "id") ?? Json.StringAt(item, "name");
            if (string.IsNullOrWhiteSpace(id)) continue;
            result.Add(new UpstreamModel(id.Trim(), null));
        }
        return result;
    }

    /// <summary>Claude：{ data: [ { id, display_name } ] }。</summary>
    private static IReadOnlyList<UpstreamModel> ParseClaude(JsonNode node)
    {
        var items = Json.At(node, "data") as JsonArray;
        if (items is null) return Array.Empty<UpstreamModel>();

        var result = new List<UpstreamModel>();
        foreach (var item in items)
        {
            var id = Json.StringAt(item, "id");
            if (string.IsNullOrWhiteSpace(id)) continue;
            result.Add(new UpstreamModel(id.Trim(), Json.StringAt(item, "display_name")));
        }
        return result;
    }

    /// <summary>Gemini：{ models: [ { name: "models/x", displayName, supportedGenerationMethods } ] }。</summary>
    private static IReadOnlyList<UpstreamModel> ParseGemini(JsonNode node)
    {
        var items = Json.At(node, "models") as JsonArray;
        if (items is null) return Array.Empty<UpstreamModel>();

        var result = new List<UpstreamModel>();
        foreach (var item in items)
        {
            var name = Json.StringAt(item, "name");
            if (string.IsNullOrWhiteSpace(name)) continue;

            // 只保留支持内容生成的模型（嵌入模型不能用于对话）
            var methods = Json.At(item, "supportedGenerationMethods") as JsonArray;
            if (methods is not null &&
                methods.All(m => Json.StringAt(m) is not { } method || method != "generateContent"))
            {
                continue;
            }

            var id = name.StartsWith("models/", StringComparison.OrdinalIgnoreCase)
                ? name["models/".Length..]
                : name;
            result.Add(new UpstreamModel(id, Json.StringAt(item, "displayName")));
        }
        return result;
    }
}
