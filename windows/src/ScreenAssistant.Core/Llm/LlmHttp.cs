using System.Net;
using System.Text;

namespace ScreenAssistant.Core.Llm;

/// <summary>HTTP 层公共逻辑：状态码到错误类型的映射、SSE 行读取。</summary>
public static class LlmHttp
{
    public static LlmException MapStatus(HttpStatusCode status, string body)
    {
        var detail = Truncate(body, 600);
        return status switch
        {
            HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden =>
                new LlmException(LlmErrorKind.Authentication, $"认证失败（{(int)status}），请检查 API Key。{detail}", (int)status),
            HttpStatusCode.TooManyRequests =>
                new LlmException(LlmErrorKind.RateLimited, $"请求过于频繁或额度不足（429）。{detail}", 429),
            HttpStatusCode.BadRequest or HttpStatusCode.NotFound or HttpStatusCode.UnprocessableEntity =>
                new LlmException(LlmErrorKind.BadRequest, $"请求被拒绝（{(int)status}），请检查模型名与参数。{detail}", (int)status),
            _ when (int)status >= 500 =>
                new LlmException(LlmErrorKind.Server, $"服务端错误（{(int)status}）。{detail}", (int)status),
            _ => new LlmException(LlmErrorKind.Unknown, $"请求失败（{(int)status}）。{detail}", (int)status)
        };
    }

    public static LlmException WrapHttpException(HttpRequestException ex, CancellationToken ct)
    {
        if (ct.IsCancellationRequested)
        {
            return new LlmException(LlmErrorKind.Cancelled, "已取消", inner: ex);
        }
        if (ex.InnerException is TimeoutException || ex.HttpRequestError == HttpRequestError.ConnectionError
            && ex.Message.Contains("timeout", StringComparison.OrdinalIgnoreCase))
        {
            return new LlmException(LlmErrorKind.Timeout, "网络请求超时。", inner: ex);
        }
        return new LlmException(LlmErrorKind.Network, $"网络错误：{ex.Message}", inner: ex);
    }

    public static async Task<Stream> EnsureSuccessAsync(HttpResponseMessage response, CancellationToken ct)
    {
        if (response.IsSuccessStatusCode)
        {
            return await response.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        }

        var body = "";
        try
        {
            body = await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        }
        catch
        {
            // 忽略读取错误正文时的异常
        }
        response.Dispose();
        throw MapStatus(response.StatusCode, body);
    }

    /// <summary>按行读取 SSE 流，返回 "data: ..." 后的内容；忽略注释与空行。</summary>
    public static async IAsyncEnumerable<string> ReadSseDataAsync(
        Stream stream,
        [System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken ct)
    {
        using var reader = new StreamReader(stream, Encoding.UTF8);
        while (true)
        {
            ct.ThrowIfCancellationRequested();
            var line = await reader.ReadLineAsync(ct).ConfigureAwait(false);
            if (line is null)
            {
                yield break;
            }
            if (line.Length == 0 || line[0] == ':')
            {
                continue;
            }
            if (line.StartsWith("data:", StringComparison.Ordinal))
            {
                yield return line[5..].Trim();
            }
        }
    }

    public static string Truncate(string? text, int max)
    {
        if (string.IsNullOrEmpty(text)) return "";
        text = text.Trim();
        return text.Length <= max ? text : text[..max] + "…";
    }
}
