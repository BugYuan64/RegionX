using System.Collections.ObjectModel;
using System.IO;
using System.Windows.Media;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using ScreenAssistant.App.Services;
using ScreenAssistant.Core.Models;

namespace ScreenAssistant.App.ViewModels;

/// <summary>详情页左侧任务列表里的一项。</summary>
public sealed class SiblingTaskItem
{
    public required TaskRecord Record { get; init; }
    public string Title => string.IsNullOrWhiteSpace(Record.Title) ? Record.Kind.ToDisplayName() : Record.Title;
    public string Summary => Record.FirstLine.Length > 0 ? Record.FirstLine : Record.Summary;
    public string FirstLine => Summary;
    public bool IsCurrent { get; init; }
    public string Id => Record.Id;
}

/// <summary>任务详情页：截图、所选操作、AI 回复，以及底部追问输入框。</summary>
public sealed partial class TaskDetailViewModel : ObservableObject
{
    private readonly WorkspaceService _workspace;
    private CancellationTokenSource? _followUpCts;

    public TaskDetailViewModel(WorkspaceService workspace, TaskRecord task)
    {
        _workspace = workspace;
        Task = task;
        ImageFullPath = workspace.ResolveImagePath(task.ImagePath);
        Kind = task.Kind;
        KindText = task.Kind.ToDisplayName();
        Title = string.IsNullOrWhiteSpace(task.Title) ? KindText : task.Title;
        ModelName = string.IsNullOrWhiteSpace(task.ModelName) ? "—" : task.ModelName;
        TimeText = task.CreatedAt.LocalDateTime.ToString("HH:mm");
        RegionSizeText = task.RegionWidth > 0
            ? $"{task.RegionWidth} × {task.RegionHeight}"
            : "—";
        StatusText = task.Status.ToDisplayName();
        Status = task.Status;
        IncludeImageOnFollowUp = workspace.IncludeImageOnFollowUp;
    }

    public TaskRecord Task { get; }
    public string? ImageFullPath { get; }
    public TaskKind Kind { get; }
    public string KindText { get; }
    public string Title { get; }
    public string ModelName { get; }
    public string TimeText { get; }
    public string RegionSizeText { get; }
    public string SessionName { get; private set; } = "";

    [ObservableProperty]
    private string _statusText;

    [ObservableProperty]
    private TaskState _status;

    [ObservableProperty]
    private string _followUpText = "";

    [ObservableProperty]
    private bool _isStreaming;

    [ObservableProperty]
    private string? _error;

    [ObservableProperty]
    private bool _includeImageOnFollowUp;

    [ObservableProperty]
    private int _siblingCount;

    public ObservableCollection<MessageItemViewModel> Messages { get; } = new();

    public ObservableCollection<SiblingTaskItem> Siblings { get; } = new();

    public ImageSource? Image => string.IsNullOrEmpty(ImageFullPath) || !File.Exists(ImageFullPath)
        ? null
        : ScreenAssistant.Platform.ScreenCaptureService.CreateBitmapSource(File.ReadAllBytes(ImageFullPath));

    public bool CanSend => !IsStreaming && !string.IsNullOrWhiteSpace(FollowUpText);

    /// <summary>请求打开同会话的另一个任务（由宿主处理）。</summary>
    public Func<string, Task>? OpenTaskRequested { get; set; }

    /// <summary>请求关闭详情（返回会话列表），由宿主注入。</summary>
    public Action? CloseRequested { get; set; }

    public event EventHandler? ScrollToEndRequested;

    public async Task LoadAsync()
    {
        // 首条用户消息的截图已在顶部单独展示，这里不再重复
        var items = await _workspace.LoadMessageItemsAsync(Task.Id, hideFirstImage: true);
        MainAnswer = items.FirstOrDefault(m => m.IsAssistant)?.Content ?? Task.FirstLine;
        Messages.Clear();
        // 首条用户提问就是「我的要求」，首条助手消息是 AI 回复，都单独展示；
        // 这里只放追问轮次，避免重复。
        foreach (var item in items.Skip(2)) Messages.Add(item);

        var session = await _workspace.LoadSessionAsync(Task.SessionId);
        SessionName = session is null ? "" : FormatSessionName(session.Name);

        var tasks = await _workspace.LoadTasksAsync(Task.SessionId);
        Siblings.Clear();
        foreach (var record in tasks)
        {
            Siblings.Add(new SiblingTaskItem
            {
                Record = record,
                IsCurrent = record.Id == Task.Id
            });
        }
        SiblingCount = tasks.Count;

        ScrollToEndRequested?.Invoke(this, EventArgs.Empty);
    }

    private static string FormatSessionName(string name)
    {
        if (!DateTime.TryParse(name, out var parsed)) return name;
        var today = DateTime.Today;
        if (parsed.Date == today) return parsed.ToString("今天 HH:mm");
        if (parsed.Date == today.AddDays(-1)) return parsed.ToString("昨天 HH:mm");
        return parsed.Year == today.Year ? parsed.ToString("M月d日 HH:mm") : parsed.ToString("yyyy年M月d日");
    }

    public void ApplyTaskUpdate(TaskRecord task)
    {
        Status = task.Status;
        StatusText = task.Status.ToDisplayName();
        Error = task.Error;
    }

    [RelayCommand]
    private void CancelFollowUp() => _followUpCts?.Cancel();

    [RelayCommand]
    private async Task SendFollowUpAsync()
    {
        var text = FollowUpText.Trim();
        if (text.Length == 0 || IsStreaming) return;

        FollowUpText = "";
        IsStreaming = true;
        Error = null;
        OnPropertyChanged(nameof(CanSend));

        Messages.Add(new MessageItemViewModel(new MessageRecord
        {
            TaskId = Task.Id,
            Role = ChatRole.User,
            Content = text
        }, null));

        var assistantMessage = new MessageItemViewModel(new MessageRecord
        {
            TaskId = Task.Id,
            Role = ChatRole.Assistant,
            Content = ""
        }, null);
        Messages.Add(assistantMessage);
        ScrollToEndRequested?.Invoke(this, EventArgs.Empty);

        _followUpCts = new CancellationTokenSource();
        try
        {
            await _workspace.FollowUpAsync(
                Task,
                text,
                accumulated =>
                {
                    assistantMessage.Content = accumulated;
                    ScrollToEndRequested?.Invoke(this, EventArgs.Empty);
                },
                _followUpCts.Token);
        }
        catch (OperationCanceledException)
        {
            if (assistantMessage.Content.Length == 0)
            {
                assistantMessage.Content = "（已停止）";
            }
        }
        catch (Exception ex)
        {
            Error = ex.Message;
            if (assistantMessage.Content.Length == 0)
            {
                Messages.Remove(assistantMessage);
            }
            else
            {
                assistantMessage.Content += "\n\n（生成中断）";
            }
        }
        finally
        {
            _followUpCts?.Dispose();
            _followUpCts = null;
            IsStreaming = false;
            OnPropertyChanged(nameof(CanSend));
            ScrollToEndRequested?.Invoke(this, EventArgs.Empty);
        }
    }

    public async Task OpenSiblingAsync(string taskId)
    {
        if (OpenTaskRequested is not null) await OpenTaskRequested(taskId);
    }

    /// <summary>首次回答正文（顶部「AI 回复」区域与复制都用它，不含追问）。</summary>
    [ObservableProperty]
    private string _mainAnswer = "";

    public string AnswerText => MainAnswer;

    /// <summary>「重新回答」：沿用原截图重新排队。</summary>
    public async Task RetryAsync()
    {
        var retried = await _workspace.RetryAsync(Task.Id);
        if (retried is null)
        {
            Error = "没有可用的模型配置，请先在设置中添加模型。";
            return;
        }

        Task.Status = retried.Status;
        ApplyTaskUpdate(retried);
    }

    /// <summary>把界面上勾选的「追问附带原截图」写回设置。</summary>
    public void ApplyFollowUpOption() => _workspace.IncludeImageOnFollowUp = IncludeImageOnFollowUp;

    partial void OnFollowUpTextChanged(string value) => OnPropertyChanged(nameof(CanSend));
}
