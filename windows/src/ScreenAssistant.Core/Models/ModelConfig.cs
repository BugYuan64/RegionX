namespace ScreenAssistant.Core.Models;

public sealed class ModelConfig
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Name { get; set; } = "";
    public LlmProvider Provider { get; set; } = LlmProvider.OpenAiCompatible;

    /// <summary>OpenAI 兼容接口的基础地址（不含 /chat/completions），如 https://api.openai.com/v1。</summary>
    public string BaseUrl { get; set; } = "";

    public string ApiKey { get; set; } = "";
    public string ModelName { get; set; } = "";
    public bool SupportsImages { get; set; } = true;

    public double? Temperature { get; set; }
    public int? MaxTokens { get; set; }

    /// <summary>附加请求头，每行 "Name: Value"。</summary>
    public string? ExtraHeaders { get; set; }

    public ModelConfig Clone() => (ModelConfig)MemberwiseClone();

    public static string DefaultOpenAiBaseUrl => "https://api.openai.com/v1";
    public static string DefaultClaudeBaseUrl => "https://api.anthropic.com";
    public static string DefaultGeminiBaseUrl => "https://generativelanguage.googleapis.com";
}
