using System.Collections.ObjectModel;
using System.Windows;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using ScreenAssistant.App.Services;
using ScreenAssistant.Core.Models;

namespace ScreenAssistant.App.ViewModels;

/// <summary>下拉框里的一个选项 = 设置里的一条模型配置。</summary>
public sealed class ModelChoice
{
    /// <summary>该配置当前使用的模型名（品牌图标按它匹配）。</summary>
    public required string ModelName { get; init; }

    /// <summary>配置 Id（作为 SelectedModelId 的值）。</summary>
    public required string ModelId { get; init; }

    /// <summary>设置里的配置名称（菜单主文本）。</summary>
    public required string ProviderName { get; init; }

    /// <summary>是否为当前正在使用的配置（右侧显示对勾）。</summary>
    public bool IsSelected { get; init; }

    /// <summary>主文本与模型名不同时才显示的副文本。</summary>
    public string? SubText =>
        string.IsNullOrWhiteSpace(ModelName) || string.Equals(ModelName, ProviderName, StringComparison.OrdinalIgnoreCase)
            ? null
            : ModelName;
}

/// <summary>识别框 + 工具栏 + 任务队列共享的状态。</summary>
public sealed partial class OverlayViewModel : ObservableObject
{
    private readonly WorkspaceService _workspace;
    private readonly SettingsService _settings;
    private readonly Func<Int32Rect, CancellationToken, Task<byte[]>> _captureAsync;

    private SessionItemViewModel? _session;

    public OverlayViewModel(
        WorkspaceService workspace,
        SettingsService settings,
        Func<Int32Rect, CancellationToken, Task<byte[]>> captureAsync)
    {
        _workspace = workspace;
        _settings = settings;
        _captureAsync = captureAsync;
    }

    public ObservableCollection<TaskItemViewModel> Tasks { get; } = new();

    [ObservableProperty]
    private bool _isTopmost = true;

    [ObservableProperty]
    private string _modelName = "未配置模型";

    /// <summary>队列标题显示的会话名（UI 方案里是「今天 22:30」这种相对时间）。</summary>
    [ObservableProperty]
    private string _sessionName = "";

    [ObservableProperty]
    private string _statusMessage = "";

    [ObservableProperty]
    private bool _isBusy;

    [ObservableProperty]
    private bool _hasSession;

    public SessionItemViewModel? Session => _session;

    /// <summary>请求打开设置（由 App 提供实现）。</summary>
    public Func<Task>? OpenSettingsRequested { get; set; }

    /// <summary>退出识别框（由 App 提供实现）。</summary>
    public Action? ExitRequested { get; set; }

    /// <summary>任务卡片需要打开详情（主窗口详情页）。</summary>
    public Func<string, Task>? OpenTaskDetailRequested { get; set; }

    public event EventHandler<TaskItemViewModel>? TaskAdded;

    public async Task InitializeAsync(SessionItemViewModel session)
    {
        _session = session;
        HasSession = true;
        SessionName = FormatSessionName(session.Name, session.CreatedAt);

        // 订阅任务状态与流式文本，否则卡片会一直停在「排队中」
        _workspace.TaskUpdated -= OnWorkspaceTaskUpdated;
        _workspace.TaskUpdated += OnWorkspaceTaskUpdated;
        _workspace.TaskStreaming -= OnWorkspaceTaskStreaming;
        _workspace.TaskStreaming += OnWorkspaceTaskStreaming;

        var model = await _workspace.GetActiveModelAsync();
        ModelName = DisplayNameOf(model);
        IsTopmost = _settings.Settings.OverlayTopmost;

        Tasks.Clear();
        var existing = await _workspace.LoadTasksAsync(session.Id);
        foreach (var record in existing)
        {
            Tasks.Add(CreateItem(record));
        }
    }

    public void RefreshModelName()
    {
        _ = RefreshModelNameAsync();
    }

    private void OnWorkspaceTaskUpdated(object? sender, TaskRecord record)
    {
        // 事件可能来自后台线程，切回 UI 线程再改绑定集合
        if (!System.Windows.Application.Current.Dispatcher.CheckAccess())
        {
            System.Windows.Application.Current.Dispatcher.Invoke(() => OnTaskUpdated(record));
            return;
        }
        OnTaskUpdated(record);
    }

    private void OnWorkspaceTaskStreaming(object? sender, (string TaskId, string Accumulated) e)
    {
        if (!System.Windows.Application.Current.Dispatcher.CheckAccess())
        {
            System.Windows.Application.Current.Dispatcher.Invoke(() => OnTaskStreaming(e.TaskId, e.Accumulated));
            return;
        }
        OnTaskStreaming(e.TaskId, e.Accumulated);
    }

    /// <summary>会话切换时解绑，避免旧 ViewModel 继续接收事件。</summary>
    public void Detach()
    {
        _workspace.TaskUpdated -= OnWorkspaceTaskUpdated;
        _workspace.TaskStreaming -= OnWorkspaceTaskStreaming;
    }

    /// <summary>会话名是时间字符串时转成「今天 22:30」，用户改过名则原样显示。</summary>
    private static string FormatSessionName(string name, DateTimeOffset createdAt)
    {
        if (!DateTime.TryParse(name, out var parsed)) return name;

        var local = parsed;
        var today = DateTime.Today;
        if (local.Date == today) return local.ToString("今天 HH:mm");
        if (local.Date == today.AddDays(-1)) return local.ToString("昨天 HH:mm");
        if (local.Year == today.Year) return local.ToString("M月d日 HH:mm");
        return local.ToString("yyyy年M月d日");
    }

    private async Task RefreshModelNameAsync()
    {
        var model = await _workspace.GetActiveModelAsync();
        ModelName = DisplayNameOf(model);
    }

    /// <summary>工具栏「模型」按钮显示的文字：优先模型名，没有时退回配置名。</summary>
    private static string DisplayNameOf(ModelConfig? model)
    {
        if (model is null) return "未配置模型";
        return string.IsNullOrWhiteSpace(model.ModelName) ? model.Name : model.ModelName;
    }

    private TaskItemViewModel CreateItem(TaskRecord record) => new(
        record,
        _workspace.ResolveImagePath(record.ImagePath),
        new TaskItemActions
        {
            Open = async item =>
            {
                if (OpenTaskDetailRequested is not null) await OpenTaskDetailRequested(item.Id);
            },
            Retry = async item =>
            {
                var retried = await _workspace.RetryAsync(item.Id);
                if (retried is null)
                {
                    StatusMessage = "没有可用的模型配置。";
                    return;
                }
                item.UpdateFromRecord();
                item.SetStreaming("");
            },
            Cancel = async item => await _workspace.CancelAsync(item.Id),
            LoadDetail = async item =>
            {
                var messages = await _workspace.LoadMessageItemsAsync(item.Id, hideFirstImage: true);
                item.FollowUpMessages.Clear();
                // 第一条助手消息是首次回答，之后的才是追问轮次
                item.FullAnswer = messages.FirstOrDefault(m => m.IsAssistant)?.Content ?? item.Record.FirstLine;
                foreach (var message in messages.Skip(2)) item.FollowUpMessages.Add(message);
            },
            FollowUp = async (item, text, assistant, ct) =>
            {
                var record = (await _workspace.LoadTaskAsync(item.Id)) ?? item.Record;
                await _workspace.FollowUpAsync(record, text, accumulated => assistant.Content = accumulated, ct);
            },
            Delete = async item =>
            {
                await _workspace.DeleteTaskAsync(item.Id);
                Tasks.Remove(item);
            }
        });

    /// <summary>核心流程：立即截图 → 建任务 → 入队（不等待模型）。</summary>
    public async Task CaptureAndQueueAsync(TaskKind kind, Int32Rect pixelBounds, int captureDelayMs = 0)
    {
        if (_session is null)
        {
            StatusMessage = "会话未初始化。";
            return;
        }
        if (IsBusy) return;

        var model = await _workspace.GetActiveModelAsync();
        if (model is null)
        {
            StatusMessage = "请先在设置中添加模型配置。";
            return;
        }

        IsBusy = true;
        try
        {
            byte[] png;
            try
            {
                if (captureDelayMs > 0) await Task.Delay(captureDelayMs);
                png = await _captureAsync(pixelBounds, CancellationToken.None);
            }
            catch (Exception ex)
            {
                StatusMessage = $"截图失败：{ex.Message}";
                return;
            }

            if (png.Length == 0)
            {
                StatusMessage = "识别框内没有可截取的内容。";
                return;
            }

            var saved = await _workspace.SaveCaptureAsync(png, CancellationToken.None);
            var record = await _workspace.EnqueueTaskAsync(
                _session.Record,
                kind,
                saved,
                new CapturedRegionInfo(pixelBounds.X, pixelBounds.Y, pixelBounds.Width, pixelBounds.Height),
                model,
                CancellationToken.None);

            var item = CreateItem(record);
            Tasks.Add(item);
            _session.Tasks.Add(item);
            _session.TaskCount = _session.Tasks.Count;
            _session.RefreshActiveState();
            TaskAdded?.Invoke(this, item);
            StatusMessage = $"{kind.ToDisplayName()}任务已加入队列";
        }
        catch (Exception ex)
        {
            StatusMessage = $"提交失败：{ex.Message}";
        }
        finally
        {
            IsBusy = false;
        }
    }

    /// <summary>由 WorkspaceService 的事件驱动：更新卡片状态与流式文本。</summary>
    public void OnTaskUpdated(TaskRecord record)
    {
        var item = Tasks.FirstOrDefault(t => t.Id == record.Id);
        if (item is null) return;

        item.Record.Title = record.Title;
        item.Record.Summary = record.Summary;
        item.Record.FirstLine = record.FirstLine;
        item.Record.Status = record.Status;
        item.Record.Error = record.Error;
        item.UpdateFromRecord();
    }

    /// <summary>列表里找不到时（例如从主程序重试）补一张卡片。</summary>
    public bool HasTask(string id) => Tasks.Any(t => t.Id == id);

    public TaskItemViewModel? FindTask(string id) => Tasks.FirstOrDefault(t => t.Id == id);

    public void AddMissing(TaskRecord record)
    {
        if (HasTask(record.Id)) return;
        Tasks.Add(CreateItem(record));
    }

    public void OnTaskStreaming(string taskId, string accumulated)
    {
        var item = Tasks.FirstOrDefault(t => t.Id == taskId);
        item?.SetStreaming(accumulated);
    }

    /// <summary>
    /// 「模型」下拉框的选项：直接来自设置里已保存的模型配置，
    /// 每项左侧用该配置的模型名匹配品牌图标，当前在用的配置右侧打勾。
    /// </summary>
    public async Task<IReadOnlyList<ModelChoice>> LoadSelectableModelsAsync()
    {
        var configs = await _workspace.LoadModelConfigsAsync();
        if (configs.Count == 0) return Array.Empty<ModelChoice>();

        var activeId = _settings.Settings.ActiveModelId;

        // 未设置或指向已删除的配置时，实际生效的是第一条（与 GetActiveModelAsync 一致）
        if (string.IsNullOrEmpty(activeId) || configs.All(c => c.Id != activeId))
        {
            activeId = configs[0].Id;
        }

        return configs
            .Select(config => new ModelChoice
            {
                ModelName = config.ModelName,
                ModelId = config.Id,
                ProviderName = config.Name,
                IsSelected = config.Id == activeId
            })
            .ToList();
    }

    /// <summary>选中下拉框里的一条模型配置：只切换「当前模型」，不改该配置保存的模型名。</summary>
    public async Task SelectModelAsync(ModelChoice choice)
    {
        var config = await _workspace.GetModelAsync(choice.ModelId);
        if (config is null)
        {
            StatusMessage = "该模型配置已被删除。";
            return;
        }

        _settings.Update(s => s.ActiveModelId = config.Id);
        await _workspace.SendModelSettingsAsync(_settings.Settings);
        ModelName = DisplayNameOf(config);
        StatusMessage = $"已切换到「{config.Name}」";
    }

    [RelayCommand]
    private void ToggleTopmost()
    {
        IsTopmost = !IsTopmost;
        _settings.Update(s => s.OverlayTopmost = IsTopmost);
        StatusMessage = IsTopmost ? "已置顶" : "已取消置顶";
    }

    [RelayCommand]
    private async Task OpenSettingsAsync()
    {
        if (OpenSettingsRequested is not null) await OpenSettingsRequested();
        RefreshModelName();
    }

    [RelayCommand]
    private void Exit() => ExitRequested?.Invoke();
}
