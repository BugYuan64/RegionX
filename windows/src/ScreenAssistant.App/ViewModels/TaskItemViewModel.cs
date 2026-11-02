using System.IO;
using System.Windows.Media;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using ScreenAssistant.Core.Models;
using ScreenAssistant.Core.Text;

namespace ScreenAssistant.App.ViewModels;

public sealed class TaskItemActions
{
    public Func<TaskItemViewModel, Task>? Open { get; init; }
    public Func<TaskItemViewModel, Task>? Retry { get; init; }
    public Func<TaskItemViewModel, Task>? Cancel { get; init; }
    public Func<TaskItemViewModel, Task>? Delete { get; init; }

    /// <summary>展开时加载完整对话（含追问）。</summary>
    public Func<TaskItemViewModel, Task>? LoadDetail { get; init; }

    /// <summary>在展开框里追问；回调把流式文本写入占位助手消息。</summary>
    public Func<TaskItemViewModel, string, MessageItemViewModel, CancellationToken, Task>? FollowUp { get; init; }
}

/// <summary>一个任务卡片：任务队列、主程序会话列表共用。</summary>
public sealed partial class TaskItemViewModel : ObservableObject
{
    private readonly TaskItemActions _actions;
    private ImageSource? _thumbnail;
    private bool _thumbnailLoaded;
    private CancellationTokenSource? _followUpCts;

    public TaskItemViewModel(TaskRecord record, string? imageFullPath, TaskItemActions? actions = null)
    {
        Record = record;
        ImageFullPath = imageFullPath;
        _actions = actions ?? new TaskItemActions();

        Title = string.IsNullOrWhiteSpace(record.Title) ? record.Kind.ToDisplayName() : record.Title;
        Summary = record.Summary;
        Status = record.Status;
        Error = record.Error;
        ModelName = record.ModelName;
        FirstLine = record.FirstLine;
        PreviewText = record.Summary;
    }

    public TaskRecord Record { get; }

    public string Id => Record.Id;
    public string SessionId => Record.SessionId;
    public TaskKind Kind => Record.Kind;
    public DateTimeOffset CreatedAt => Record.CreatedAt;
    public string CreatedTime => CreatedAt.ToLocalTime().ToString("HH:mm:ss");
    public string CreatedTimeTooltip => CreatedAt.ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss");
    public string? ImageFullPath { get; }
    public string KindText => Record.Kind.ToDisplayName();

    /// <summary>回答首行（识别框队列里的一行展示）。</summary>
    [ObservableProperty]
    private string _firstLine;

    [ObservableProperty]
    private string _title;

    [ObservableProperty]
    private string _summary;

    [ObservableProperty]
    private TaskState _status;

    [ObservableProperty]
    private string _previewText;

    [ObservableProperty]
    private string _modelName;

    [ObservableProperty]
    private string? _error;

    /// <summary>是否展开完整回答（含追问）。</summary>
    [ObservableProperty]
    private bool _isExpanded;

    /// <summary>流式过程中的实时文本（空表示尚未收到内容）。</summary>
    [ObservableProperty]
    private string _streamingText = "";

    /// <summary>展开区：完整回答（首次回答正文）。</summary>
    [ObservableProperty]
    private string _fullAnswer = "";

    /// <summary>展开区：追问轮次。</summary>
    public System.Collections.ObjectModel.ObservableCollection<MessageItemViewModel> FollowUpMessages { get; } = new();

    [ObservableProperty]
    private bool _isLoadingDetail;

    [ObservableProperty]
    private string _followUpText = "";

    [ObservableProperty]
    private bool _isFollowingUp;

    public ImageSource? Thumbnail
    {
        get
        {
            if (_thumbnailLoaded) return _thumbnail;
            _thumbnailLoaded = true;

            if (string.IsNullOrEmpty(ImageFullPath) || !File.Exists(ImageFullPath)) return null;
            try
            {
                _thumbnail = ScreenAssistant.Platform.ScreenCaptureService.CreateBitmapSource(
                    File.ReadAllBytes(ImageFullPath));
            }
            catch (IOException)
            {
                _thumbnail = null;
            }
            return _thumbnail;
        }
    }

    public bool IsActive => Status is TaskState.Queued or TaskState.Running;
    public bool CanCancel => Status is TaskState.Queued or TaskState.Running;
    public bool CanRetry => Status is TaskState.Failed or TaskState.Cancelled;
    public bool HasError => !string.IsNullOrWhiteSpace(Error);
    public bool HasStreaming => StreamingText.Length > 0;

    /// <summary>已完成才显示首行回答与展开箭头。</summary>
    public bool HasResultLine => Status is TaskState.Completed or TaskState.Cancelled or TaskState.Failed;

    /// <summary>
    /// 任务卡片与历史会话共用的那行小号字：
    /// 处理中显示流式输出（末尾带光标），完成后显示 **AI 回复的第一行**。
    /// 注意：不是 20 字简介（Summary），Summary 只在需要时作占位。
    /// </summary>
    public string ResultLine
    {
        get
        {
            if (Status == TaskState.Running)
            {
                // 流式输出末尾加光标，提示还在生成
                if (StreamingText.Length > 0) return StreamingText + StreamingCaret;
                return "正在识别…";
            }
            if (FirstLine.Length > 0) return FirstLine;
            if (Status is TaskState.Queued) return "等待上一个任务完成";
            if (Status is TaskState.Failed) return Error is { Length: > 0 } e ? e : "处理失败，可重试";
            // 还没有首行时（例如刚开始流式输出前）用简介兜底
            return Summary;
        }
    }

    /// <summary>流式输出末尾的光标。</summary>
    private const string StreamingCaret = " ▍";

    public bool CanSendFollowUp => !IsFollowingUp && !string.IsNullOrWhiteSpace(FollowUpText);

    public void UpdateFromRecord()
    {
        // 标题统一来自任务记录（≤10 字总结）
        Title = string.IsNullOrWhiteSpace(Record.Title) ? Record.Kind.ToDisplayName() : Record.Title;
        Summary = Record.Summary;
        // 小号字统一来自 AI 回复首行；流式过程中先用已收到的内容推首行
        FirstLine = Record.FirstLine.Length > 0
            ? Record.FirstLine
            : TitleGenerator.FirstLine(StreamingText);
        ModelName = Record.ModelName;
        Status = Record.Status;
        Error = Record.Error;
        if (Status != TaskState.Running) PreviewText = Summary;
        RaiseStateFlags();
    }

    public void SetStreaming(string text)
    {
        StreamingText = text;
        PreviewText = text;
        RaiseStateFlags();
    }

    private void RaiseStateFlags()
    {
        OnPropertyChanged(nameof(IsActive));
        OnPropertyChanged(nameof(CanCancel));
        OnPropertyChanged(nameof(CanRetry));
        OnPropertyChanged(nameof(HasError));
        OnPropertyChanged(nameof(HasStreaming));
        OnPropertyChanged(nameof(HasResultLine));
        OnPropertyChanged(nameof(ResultLine));
    }

    partial void OnFirstLineChanged(string value) => OnPropertyChanged(nameof(ResultLine));
    partial void OnStreamingTextChanged(string value) => OnPropertyChanged(nameof(ResultLine));
    partial void OnFollowUpTextChanged(string value) => OnPropertyChanged(nameof(CanSendFollowUp));
    partial void OnIsFollowingUpChanged(bool value)
    {
        OnPropertyChanged(nameof(CanSendFollowUp));
        OnPropertyChanged(nameof(CanStopFollowUp));
    }

    public bool CanStopFollowUp => IsFollowingUp;

    partial void OnIsExpandedChanged(bool value)
    {
        if (value) _ = EnsureDetailAsync();
    }

    /// <summary>首次展开时加载追问记录。</summary>
    public async Task EnsureDetailAsync()
    {
        if (_actions.LoadDetail is null || FollowUpMessages.Count > 0) return;

        IsLoadingDetail = true;
        try
        {
            await _actions.LoadDetail(this);
        }
        finally
        {
            IsLoadingDetail = false;
        }
    }

    [RelayCommand]
    private void ToggleExpand() => IsExpanded = !IsExpanded;

    [RelayCommand]
    private async Task OpenAsync()
    {
        if (_actions.Open is not null) await _actions.Open(this);
    }

    [RelayCommand]
    private async Task RetryAsync()
    {
        if (_actions.Retry is not null) await _actions.Retry(this);
    }

    [RelayCommand]
    private async Task CancelAsync()
    {
        if (_actions.Cancel is not null) await _actions.Cancel(this);
    }

    [RelayCommand]
    private async Task DeleteAsync()
    {
        if (_actions.Delete is not null) await _actions.Delete(this);
    }

    [RelayCommand]
    private void StopFollowUp() => _followUpCts?.Cancel();

    [RelayCommand]
    private async Task SendFollowUpAsync()
    {
        var text = FollowUpText.Trim();
        if (text.Length == 0 || IsFollowingUp || _actions.FollowUp is null) return;

        FollowUpText = "";
        IsFollowingUp = true;

        var userMessage = new MessageItemViewModel(new MessageRecord
        {
            TaskId = Record.Id,
            Role = ChatRole.User,
            Content = text
        }, null);
        FollowUpMessages.Add(userMessage);

        var assistant = new MessageItemViewModel(new MessageRecord
        {
            TaskId = Record.Id,
            Role = ChatRole.Assistant,
            Content = ""
        }, null);
        FollowUpMessages.Add(assistant);

        _followUpCts = new CancellationTokenSource();
        try
        {
            await _actions.FollowUp(this, text, assistant, _followUpCts.Token);
        }
        catch (OperationCanceledException)
        {
            if (assistant.Content.Length == 0) assistant.Content = "（已停止）";
        }
        catch (Exception ex)
        {
            if (assistant.Content.Length == 0)
            {
                FollowUpMessages.Remove(assistant);
                Error = ex.Message;
            }
            else
            {
                assistant.Content += "\n\n（生成中断）";
            }
        }
        finally
        {
            _followUpCts?.Dispose();
            _followUpCts = null;
            IsFollowingUp = false;
        }
    }

    /// <summary>追问流式回调。</summary>
    public void AppendFollowUp(MessageItemViewModel message, string accumulated) => message.Content = accumulated;
}
