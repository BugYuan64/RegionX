using System.Collections.ObjectModel;
using System.IO;
using System.Text.Json;
using System.Windows;
using System.Windows.Media;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using ScreenAssistant.App.Services;
using ScreenAssistant.App.Themes;
using ScreenAssistant.Core.Llm;
using ScreenAssistant.Core.Models;
using ScreenAssistant.Data.Repositories;
using ScreenAssistant.Data;
using ScreenAssistant.Llm;

namespace ScreenAssistant.App.ViewModels;

public sealed partial class SettingsViewModel : ObservableObject
{
    private readonly SettingsService _settings;
    private readonly IModelConfigRepository _models;
    private readonly IHttpClientProvider _http;
    private readonly IModelCatalog _catalog;
    private readonly IThemeRegistry _themes;

    public SettingsViewModel(
        SettingsService settings,
        IModelConfigRepository models,
        IHttpClientProvider http,
        IModelCatalog catalog,
        IThemeRegistry themes,
        AppPaths paths)
    {
        _settings = settings;
        _models = models;
        _http = http;
        _catalog = catalog;
        _themes = themes;
        ConfigurationDirectory = paths.Root;

        BuildThemeOptions();

        var current = settings.Settings;
        AnswerPrompt = current.AnswerPrompt;
        TranslatePrompt = current.TranslatePrompt;
        TranslateTargetLanguage = current.TranslateTargetLanguage;
        ExtractPrompt = current.ExtractPrompt;
        FollowUpPrompt = current.FollowUpPrompt;
        IncludeImageOnFollowUp = current.IncludeImageOnFollowUp;
        ProxyUrl = current.ProxyUrl ?? "";
        MaxConcurrency = current.MaxConcurrency;
        TimeoutSeconds = current.TimeoutSeconds;
        CaptureDelayMs = current.CaptureDelayMs;

        _ = InitializeModelsAsync();
    }

    /// <summary>可选主题（来自主题注册表，新增主题无需改这里）。</summary>
    public ObservableCollection<ThemeOption> Themes { get; } = new();

    private void BuildThemeOptions()
    {
        Themes.Clear();
        var current = _settings.Settings.ThemeName;
        foreach (var theme in _themes.Themes)
        {
            Themes.Add(new ThemeOption(theme, string.Equals(theme.Id, current, StringComparison.OrdinalIgnoreCase)));
        }
    }

    /// <summary>点击主题卡片：立即应用并持久化。</summary>
    [RelayCommand]
    private async Task SelectThemeAsync(ThemeOption? option)
    {
        if (option is null) return;

        try
        {
            _settings.Update(s => s.ThemeName = option.Id);
            App.ApplyTheme(option.Id);

            foreach (var item in Themes) item.IsSelected = item.Id == option.Id;
            StatusMessage = $"已切换到「{option.DisplayName}」主题。";
        }
        catch (Exception ex) when (IsStorageError(ex)) { ShowSaveError(); }
        await Task.CompletedTask;
    }

    public ObservableCollection<ModelConfigViewModel> Models { get; } = new();
    public string ConfigurationDirectory { get; }

    [ObservableProperty]
    private bool _isStatusError;

    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(SaveModelCommand))]
    [NotifyCanExecuteChangedFor(nameof(SaveSettingsCommand))]
    [NotifyCanExecuteChangedFor(nameof(AddModelCommand))]
    [NotifyCanExecuteChangedFor(nameof(DeleteModelCommand))]
    [NotifyCanExecuteChangedFor(nameof(SetActiveModelCommand))]
    private bool _isSaving;

    private bool CanSave => !IsSaving;

    partial void OnStatusMessageChanged(string value) => IsStatusError = false;

    private void ShowSaveError()
    {
        StatusMessage = "保存失败，请检查配置目录的写入权限或配置文件是否有效。输入已保留，可重试。";
        IsStatusError = true;
    }

    public IReadOnlyList<LlmProviderOption> ProviderOptions { get; } = new[]
    {
        new LlmProviderOption(LlmProvider.OpenAiCompatible, "OpenAI 兼容接口"),
        new LlmProviderOption(LlmProvider.Claude, "Claude"),
        new LlmProviderOption(LlmProvider.Gemini, "Gemini")
    };

    [ObservableProperty]
    private ModelConfigViewModel? _selectedModel;

    [ObservableProperty]
    private string _answerPrompt;

    [ObservableProperty]
    private string _translatePrompt;

    [ObservableProperty]
    private string _translateTargetLanguage;

    [ObservableProperty]
    private string _extractPrompt;

    [ObservableProperty]
    private string _followUpPrompt;

    [ObservableProperty]
    private bool _includeImageOnFollowUp;

    [ObservableProperty]
    private string _proxyUrl;

    [ObservableProperty]
    private int _maxConcurrency;

    [ObservableProperty]
    private int _timeoutSeconds;

    [ObservableProperty]
    private int _captureDelayMs;

    [ObservableProperty]
    private string _statusMessage = "";

    public event EventHandler<bool>? CloseRequested;

    private ModelConfigViewModel CreateModelViewModel(ModelConfig config) =>
        new(config, LoadCatalogAsync);

    /// <summary>拉取上游模型列表；失败时把错误交给界面，允许手填模型名。</summary>
    private async Task<(IReadOnlyList<UpstreamModel> Models, string? Error)> LoadCatalogAsync(ModelConfig config)
    {
        try
        {
            var models = await _catalog.ListModelsAsync(config, CancellationToken.None);
            return (models, null);
        }
        catch (LlmException ex)
        {
            return (Array.Empty<UpstreamModel>(), ex.Message);
        }
        catch (Exception ex)
        {
            return (Array.Empty<UpstreamModel>(), $"拉取失败：{ex.Message}");
        }
    }

    private async Task InitializeModelsAsync()
    {
        try { await LoadModelsAsync(); }
        catch (Exception ex) when (IsStorageError(ex))
        {
            StatusMessage = "读取模型配置失败，请检查配置文件和目录权限。";
            IsStatusError = true;
        }
    }

    private async Task LoadModelsAsync(string? selectId = null)
    {
        var configs = await _models.GetAllAsync();
        Models.Clear();
        foreach (var config in configs)
        {
            Models.Add(CreateModelViewModel(config));
        }

        // 仅恢复明确选中的配置；保存后由调用方清空编辑区。
        var keepId = selectId ?? SelectedModel?.Config.Id;
        SelectedModel = Models.FirstOrDefault(m => m.Id == keepId);
    }

    [RelayCommand(CanExecute = nameof(CanSave))]
    private async Task AddModelAsync()
    {
        var viewModel = CreateModelViewModel(new ModelConfig
        {
            Name = "新模型",
            Provider = LlmProvider.OpenAiCompatible,
            BaseUrl = ModelConfig.DefaultOpenAiBaseUrl,
            SupportsImages = true
        });
        Models.Add(viewModel);
        SelectedModel = viewModel;
        await Task.CompletedTask;
    }

    [RelayCommand(CanExecute = nameof(CanSave))]
    private async Task SaveModelAsync()
    {
        if (SelectedModel is null) return;
        IsSaving = true;
        try
        {
            var config = await PersistSelectedModelAsync();
            if (config is null) return;
            await LoadModelsAsync();
            SelectedModel = null;
            StatusMessage = $"已保存模型「{config.Name}」，编辑区已清空。可从左侧列表重新编辑。";
        }
        catch (Exception ex) when (IsStorageError(ex)) { ShowSaveError(); }
        finally { IsSaving = false; }
    }

    private async Task<ModelConfig?> PersistSelectedModelAsync()
    {
        if (SelectedModel is null) return null;
        if (!SelectedModel.Validate(out var error))
        {
            StatusMessage = error;
            IsStatusError = true;
            return null;
        }

        var config = SelectedModel.ToConfig();
        var savedId = config.Id;
        var exists = (await _models.GetAllAsync()).Any(m => m.Id == config.Id);
        if (exists)
        {
            await _models.UpdateAsync(config);
        }
        else
        {
            await _models.InsertAsync(config);
        }

        if (string.IsNullOrEmpty(_settings.Settings.ActiveModelId))
        {
            _settings.Update(s => s.ActiveModelId = config.Id);
        }

        ModelSaved?.Invoke(this, savedId);
        return config;
    }

    private static bool IsStorageError(Exception ex) => ex is IOException or UnauthorizedAccessException
        or JsonException or System.Security.Cryptography.CryptographicException
        or Microsoft.Data.Sqlite.SqliteException or InvalidOperationException;

    /// <summary>一条模型配置已保存（用于提示刷新其他界面的模型名）。</summary>
    public event EventHandler<string>? ModelSaved;

    [RelayCommand(CanExecute = nameof(CanSave))]
    private async Task DeleteModelAsync()
    {
        if (SelectedModel is null) return;

        var result = MessageBox.Show(
            $"删除模型配置「{SelectedModel.Name}」？",
            "删除模型", MessageBoxButton.OKCancel, MessageBoxImage.Warning, MessageBoxResult.Cancel);
        if (result != MessageBoxResult.OK) return;

        try
        {
            var deletedId = SelectedModel.Config.Id;
            await _models.DeleteAsync(deletedId);
            await _models.ExportAsync();
            Models.Remove(SelectedModel);
            SelectedModel = Models.FirstOrDefault();

            if (_settings.Settings.ActiveModelId == deletedId)
            {
                _settings.Update(s => s.ActiveModelId = SelectedModel?.Config.Id);
            }

            StatusMessage = "已删除。";
        }
        catch (Exception ex) when (IsStorageError(ex)) { ShowSaveError(); }
    }

    [RelayCommand(CanExecute = nameof(CanSave))]
    private async Task SetActiveModelAsync()
    {
        if (SelectedModel is null) return;
        IsSaving = true;
        try
        {
            var config = await PersistSelectedModelAsync();
            if (config is null) return;
            _settings.Update(s => s.ActiveModelId = config.Id);
            await LoadModelsAsync();
            SelectedModel = null;
            StatusMessage = $"已保存，当前使用「{config.Name}」。";
        }
        catch (Exception ex) when (IsStorageError(ex)) { ShowSaveError(); }
        finally { IsSaving = false; }
    }

    [RelayCommand(CanExecute = nameof(CanSave))]
    private async Task SaveSettingsAsync()
    {
        var previousProxy = _settings.Settings.ProxyUrl;
        IsSaving = true;
        try
        {
            if (SelectedModel is not null && !SelectedModel.Validate(out var error))
            {
                StatusMessage = error;
                IsStatusError = true;
                return;
            }
            _http.SetProxy(ProxyUrl);

            var config = await PersistSelectedModelAsync();

            _settings.Update(s =>
            {
                s.AnswerPrompt = AnswerPrompt;
                s.TranslatePrompt = TranslatePrompt;
                s.TranslateTargetLanguage = TranslateTargetLanguage;
                s.ExtractPrompt = ExtractPrompt;
                s.FollowUpPrompt = FollowUpPrompt;
                s.IncludeImageOnFollowUp = IncludeImageOnFollowUp;
                s.ProxyUrl = string.IsNullOrWhiteSpace(ProxyUrl) ? null : ProxyUrl.Trim();
                s.MaxConcurrency = Math.Clamp(MaxConcurrency, 1, 5);
                s.TimeoutSeconds = Math.Clamp(TimeoutSeconds, 10, 600);
                s.CaptureDelayMs = Math.Clamp(CaptureDelayMs, 0, 2000);
            });

            await LoadModelsAsync();
            SelectedModel = null;
            StatusMessage = config is null ? "设置已保存到配置文件。" : $"设置和模型「{config.Name}」已保存，编辑区已清空。";
        }
        catch (ArgumentException ex)
        {
            StatusMessage = ex.Message;
            IsStatusError = true;
        }
        catch (Exception ex) when (IsStorageError(ex))
        {
            _http.SetProxy(previousProxy);
            ShowSaveError();
        }
        finally { IsSaving = false; }
    }

    [RelayCommand]
    private void Close() => CloseRequested?.Invoke(this, true);
}

/// <summary>接口类型下拉项；重写 ToString 让 WPF 在没有模板时也能显示正确文字。</summary>
public sealed record LlmProviderOption(LlmProvider Value, string Label)
{
    public override string ToString() => Label;
}
