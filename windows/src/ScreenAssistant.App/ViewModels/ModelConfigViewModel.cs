using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using ScreenAssistant.Core.Llm;
using ScreenAssistant.Core.Models;

namespace ScreenAssistant.App.ViewModels;

/// <summary>设置页里的单条模型配置。</summary>
public sealed partial class ModelConfigViewModel : ObservableObject
{
    private readonly Func<ModelConfig, Task<(IReadOnlyList<UpstreamModel> Models, string? Error)>>? _loadCatalog;

    public ModelConfigViewModel(
        ModelConfig config,
        Func<ModelConfig, Task<(IReadOnlyList<UpstreamModel> Models, string? Error)>>? loadCatalog = null)
    {
        Config = config;
        _loadCatalog = loadCatalog;
        Name = config.Name;
        Provider = config.Provider;
        BaseUrl = config.BaseUrl;
        ApiKey = config.ApiKey;
        ModelName = config.ModelName;
        SupportsImages = config.SupportsImages;
        Temperature = config.Temperature;
        MaxTokens = config.MaxTokens;
        ExtraHeaders = config.ExtraHeaders ?? "";
    }

    public ModelConfig Config { get; }

    /// <summary>配置 Id（重新载入列表后用它恢复选中项）。</summary>
    public string Id => Config.Id;

    [ObservableProperty]
    private string _name;

    [ObservableProperty]
    private LlmProvider _provider;

    [ObservableProperty]
    private string _baseUrl;

    [ObservableProperty]
    private string _apiKey;

    [ObservableProperty]
    private string _modelName;

    [ObservableProperty]
    private bool _supportsImages;

    [ObservableProperty]
    private double? _temperature;

    [ObservableProperty]
    private int? _maxTokens;

    [ObservableProperty]
    private string _extraHeaders;

    /// <summary>上游返回的模型列表（模型名下拉框的选项）。</summary>
    public ObservableCollection<string> AvailableModels { get; } = new();

    [ObservableProperty]
    private bool _isLoadingModels;

    [ObservableProperty]
    private string? _modelListError;

    public bool IsClaude => Provider == LlmProvider.Claude;

    public bool IsGemini => Provider == LlmProvider.Gemini;

    public static string PlaceholderFor(LlmProvider provider) => provider switch
    {
        LlmProvider.OpenAiCompatible => "https://api.openai.com/v1",
        LlmProvider.Claude => "https://api.anthropic.com",
        LlmProvider.Gemini => "https://generativelanguage.googleapis.com",
        _ => ""
    };

    partial void OnProviderChanged(LlmProvider value)
    {
        var defaults = new[]
        {
            ModelConfig.DefaultOpenAiBaseUrl,
            ModelConfig.DefaultClaudeBaseUrl,
            ModelConfig.DefaultGeminiBaseUrl
        };
        if (string.IsNullOrWhiteSpace(BaseUrl) || defaults.Contains(BaseUrl.Trim()))
        {
            BaseUrl = PlaceholderFor(value);
        }
        OnPropertyChanged(nameof(IsClaude));
        OnPropertyChanged(nameof(IsGemini));
        AvailableModels.Clear();
    }

    /// <summary>按当前填写的地址与 Key 拉取上游模型列表。</summary>
    [RelayCommand]
    private async Task LoadModelsAsync()
    {
        if (_loadCatalog is null) return;

        IsLoadingModels = true;
        ModelListError = null;
        try
        {
            var snapshot = new ModelConfig
            {
                Id = Config.Id,
                Name = Name,
                Provider = Provider,
                BaseUrl = BaseUrl.Trim(),
                ApiKey = ApiKey.Trim(),
                ModelName = ModelName,
                // 拉列表也要带上自定义请求头，否则需要特殊头的网关会 401/403
                ExtraHeaders = string.IsNullOrWhiteSpace(ExtraHeaders) ? null : ExtraHeaders
            };

            var (models, error) = await _loadCatalog(snapshot);
            AvailableModels.Clear();
            foreach (var model in models) AvailableModels.Add(model.Id);

            if (models.Count == 0)
            {
                ModelListError = error ?? "上游没有返回可用模型，可手动填写模型名。";
            }
            else
            {
                ModelListError = null;
                // 没填过模型名时才用列表第一项兜底；已填的就保留用户的输入
                if (string.IsNullOrWhiteSpace(ModelName))
                {
                    ModelName = AvailableModels[0];
                }
            }
        }
        finally
        {
            IsLoadingModels = false;
        }
    }

    public ModelConfig ToConfig()
    {
        var config = Config.Clone();
        config.Name = Name.Trim();
        config.Provider = Provider;
        config.BaseUrl = BaseUrl.Trim();
        config.ApiKey = ApiKey.Trim();
        config.ModelName = ModelName.Trim();
        config.SupportsImages = SupportsImages;
        config.Temperature = Temperature;
        config.MaxTokens = MaxTokens;
        config.ExtraHeaders = string.IsNullOrWhiteSpace(ExtraHeaders) ? null : ExtraHeaders;
        return config;
    }

    public bool Validate(out string error)
    {
        if (string.IsNullOrWhiteSpace(Name))
        {
            error = "请填写配置名称。";
            return false;
        }
        if (string.IsNullOrWhiteSpace(ModelName))
        {
            // 上游列表没拉到时允许手填，这里只要求非空
            error = "请填写或选择模型名（可点击「拉取模型列表」从上游获取）。";
            return false;
        }
        // Base URL 可留空（用官方地址）；填了就必须是 http/https 的绝对地址
        if (!string.IsNullOrWhiteSpace(BaseUrl))
        {
            if (!Uri.TryCreate(BaseUrl.Trim(), UriKind.Absolute, out var uri) ||
                (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps))
            {
                error = "Base URL 格式不正确，需以 http:// 或 https:// 开头（可留空使用官方地址）。";
                return false;
            }
        }
        if (string.IsNullOrWhiteSpace(ApiKey))
        {
            error = "请填写 API Key。";
            return false;
        }
        if (Temperature is < 0 or > 2 || Temperature is double temperature && !double.IsFinite(temperature))
        {
            error = "温度需在 0–2 之间，或留空。";
            return false;
        }
        if (MaxTokens is <= 0)
        {
            error = "Max Tokens 需为正整数，或留空。";
            return false;
        }
        error = "";
        return true;
    }
}
