using System.Net;
using System.Text;
using ScreenAssistant.Llm;

namespace ScreenAssistant.Tests;

/// <summary>用固定响应替代真实网络，验证适配器的请求构造与流式解析。</summary>
internal sealed class StubHttpClientProvider : IHttpClientProvider
{
    private readonly HttpClient _client;

    public StubHttpClientProvider(HttpMessageHandler handler)
    {
        _client = new HttpClient(handler) { Timeout = Timeout.InfiniteTimeSpan };
    }

    public HttpClient Client => _client;

    public void SetProxy(string? proxyUrl)
    {
    }
}

internal sealed class CapturingHandler : HttpMessageHandler
{
    private readonly Func<HttpRequestMessage, HttpResponseMessage> _responder;

    public CapturingHandler(string body, HttpStatusCode status = HttpStatusCode.OK, string contentType = "text/event-stream")
    {
        _responder = _ => new HttpResponseMessage(status)
        {
            Content = new StringContent(body, Encoding.UTF8, contentType)
        };
    }

    public CapturingHandler(Func<HttpRequestMessage, HttpResponseMessage> responder) => _responder = responder;

    public HttpRequestMessage? LastRequest { get; private set; }
    public string? LastBody { get; private set; }

    protected override async Task<HttpResponseMessage> SendAsync(
        HttpRequestMessage request,
        CancellationToken cancellationToken)
    {
        LastRequest = request;
        if (request.Content is not null)
        {
            LastBody = await request.Content.ReadAsStringAsync(cancellationToken);
        }
        return _responder(request);
    }
}
