using ScreenAssistant.Core.Llm;
using ScreenAssistant.Core.Models;
using ScreenAssistant.Llm.Adapters;

namespace ScreenAssistant.Llm;

public sealed class ChatModelClientFactory : IChatModelClientFactory
{
    private readonly IHttpClientProvider _http;

    public ChatModelClientFactory(IHttpClientProvider http) => _http = http;

    public IChatModelClient Create(ModelConfig config) => config.Provider switch
    {
        LlmProvider.OpenAiCompatible => new OpenAiCompatibleClient(config, _http.Client),
        LlmProvider.Claude => new ClaudeClient(config, _http.Client),
        LlmProvider.Gemini => new GeminiClient(config, _http.Client),
        _ => throw new NotSupportedException($"不支持的接口类型：{config.Provider}")
    };
}
