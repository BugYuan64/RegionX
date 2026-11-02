using ScreenAssistant.Core.Models;

namespace ScreenAssistant.Core.Llm;

public sealed record LlmImage(byte[] Data, string MimeType)
{
    public static LlmImage FromPng(byte[] data) => new(data, "image/png");
}

public sealed class LlmMessage
{
    public ChatRole Role { get; init; }
    public string Text { get; init; } = "";
    public IReadOnlyList<LlmImage> Images { get; init; } = Array.Empty<LlmImage>();

    public static LlmMessage User(string text, params LlmImage[] images) =>
        new() { Role = ChatRole.User, Text = text, Images = images };

    public static LlmMessage Assistant(string text) =>
        new() { Role = ChatRole.Assistant, Text = text };
}

public sealed class LlmChatRequest
{
    public required ModelConfig Model { get; init; }
    public required IReadOnlyList<LlmMessage> Messages { get; init; }
    public string? SystemPrompt { get; init; }
}

public enum LlmErrorKind
{
    Unknown,
    Network,
    Timeout,
    Authentication,
    RateLimited,
    Server,
    BadRequest,
    Protocol,
    Cancelled
}

public sealed class LlmException : Exception
{
    public LlmErrorKind Kind { get; }
    public int? StatusCode { get; }

    public LlmException(LlmErrorKind kind, string message, int? statusCode = null, Exception? inner = null)
        : base(message, inner)
    {
        Kind = kind;
        StatusCode = statusCode;
    }

    public bool IsRetryable => Kind is LlmErrorKind.Network or LlmErrorKind.Timeout
        or LlmErrorKind.RateLimited or LlmErrorKind.Server;
}

/// <summary>统一的流式对话接口。上层只依赖它，不关心具体厂商。</summary>
public interface IChatModelClient
{
    /// <summary>逐块返回模型输出的文字。取消时抛出 OperationCanceledException。</summary>
    IAsyncEnumerable<string> StreamAsync(LlmChatRequest request, CancellationToken cancellationToken);
}

/// <summary>按模型配置里的类型创建对应的适配器。</summary>
public interface IChatModelClientFactory
{
    IChatModelClient Create(ModelConfig config);
}
