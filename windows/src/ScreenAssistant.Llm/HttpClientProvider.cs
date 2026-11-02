using System.Net;

namespace ScreenAssistant.Llm;

public interface IHttpClientProvider
{
    /// <summary>全局单例 HttpClient。代理变更后会自动重建。</summary>
    HttpClient Client { get; }

    void SetProxy(string? proxyUrl);
}

public sealed class HttpClientProvider : IHttpClientProvider, IDisposable
{
    private readonly object _gate = new();
    private HttpClient _client;
    private string? _proxyUrl;

    public HttpClientProvider()
    {
        _client = Build(null);
    }

    public HttpClient Client
    {
        get { lock (_gate) return _client; }
    }

    public void SetProxy(string? proxyUrl)
    {
        if (string.IsNullOrWhiteSpace(proxyUrl)) proxyUrl = null;
        else proxyUrl = proxyUrl.Trim();

        lock (_gate)
        {
            if (string.Equals(_proxyUrl, proxyUrl, StringComparison.OrdinalIgnoreCase)) return;
            var replacement = Build(proxyUrl);
            var old = _client;
            _client = replacement;
            _proxyUrl = proxyUrl;
            old.Dispose();
        }
    }

    private static HttpClient Build(string? proxyUrl)
    {
        var handler = new SocketsHttpHandler
        {
            // 长连接复用，避免每次请求重新握手
            PooledConnectionLifetime = TimeSpan.FromMinutes(10),
            ConnectTimeout = TimeSpan.FromSeconds(20),
            AutomaticDecompression = DecompressionMethods.All,
            AllowAutoRedirect = true
        };

        if (!string.IsNullOrWhiteSpace(proxyUrl))
        {
            if (!Uri.TryCreate(proxyUrl, UriKind.Absolute, out var proxyUri))
            {
                throw new ArgumentException($"代理地址格式不正确：{proxyUrl}");
            }
            handler.Proxy = new WebProxy(proxyUri);
            handler.UseProxy = true;
        }
        else
        {
            handler.UseProxy = false;
        }

        return new HttpClient(handler, disposeHandler: true)
        {
            // 超时由每个请求的 CancellationTokenSource 控制，便于流式读取
            Timeout = Timeout.InfiniteTimeSpan
        };
    }

    public void Dispose()
    {
        lock (_gate) _client.Dispose();
    }
}
