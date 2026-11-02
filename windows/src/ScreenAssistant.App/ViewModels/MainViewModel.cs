using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Windows;
using System.Windows.Data;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using ScreenAssistant.App.Services;
using ScreenAssistant.Core.Models;
using ScreenAssistant.Data.Repositories;

namespace ScreenAssistant.App.ViewModels;

public sealed partial class MainViewModel : ObservableObject
{
    private readonly WorkspaceService _workspace;
    private readonly Func<Task>? _openSettings;

    public MainViewModel(
        WorkspaceService workspace,
        Func<Task>? openSettings = null)
    {
        _workspace = workspace;
        _openSettings = openSettings;

        // 任务状态与流式文本：主程序里展开的会话也能实时看到结果
        _workspace.TaskUpdated += OnTaskUpdated;
        _workspace.TaskStreaming += OnTaskStreaming;

        SessionsView = CollectionViewSource.GetDefaultView(Sessions);
        SessionsView.Filter = FilterSession;
    }

    public ObservableCollection<SessionItemViewModel> Sessions { get; } = new();

    /// <summary>退出前解绑任务回调，避免异步队列释放时仍等待已关闭的 UI。</summary>
    public void Detach()
    {
        _workspace.TaskUpdated -= OnTaskUpdated;
        _workspace.TaskStreaming -= OnTaskStreaming;
    }

    /// <summary>带搜索过滤的会话视图（界面绑定它）。</summary>
    public ICollectionView SessionsView { get; }

    [ObservableProperty]
    private string _searchText = "";

    public int TotalTaskCount => Sessions.Sum(s => s.TaskCount);

    private bool FilterSession(object item)
    {
        if (string.IsNullOrWhiteSpace(SearchText)) return true;
        if (item is not SessionItemViewModel session) return false;

        var key = SearchText.Trim();
        return session.Name.Contains(key, StringComparison.OrdinalIgnoreCase)
            || session.Tasks.Any(t =>
                t.Title.Contains(key, StringComparison.OrdinalIgnoreCase)
                || t.Summary.Contains(key, StringComparison.OrdinalIgnoreCase));
    }

    partial void OnSearchTextChanged(string value) => SessionsView.Refresh();

    [ObservableProperty]
    private bool _isLoading;

    [ObservableProperty]
    private string _emptyHint = "还没有会话，点击下方「开始」打开识别框。";

    [ObservableProperty]
    private string? _selectedModelName;

    /// <summary>打开识别框时创建的新会话（由 App 注入实际窗口逻辑）。</summary>
    public Func<Task<SessionItemViewModel?>>? StartOverlayHandler { get; set; }

    public async Task LoadAsync()
    {
        IsLoading = true;
        try
        {
            var rows = await _workspace.LoadSessionsAsync();
            Sessions.Clear();
            foreach (var row in rows)
            {
                Sessions.Add(CreateSessionItemFromRow(row));
            }
            EmptyHint = Sessions.Count == 0
                ? "还没有会话，点击下方「开始」打开识别框。"
                : "";

            // 最近一个会话默认展开，启动后能直接看到最近的任务
            if (Sessions.Count > 0) Sessions[0].IsExpanded = true;

            OnPropertyChanged(nameof(TotalTaskCount));
            await RefreshModelNameAsync();
        }
        finally
        {
            IsLoading = false;
        }
    }

    public async Task RefreshModelNameAsync()
    {
        var model = await _workspace.GetActiveModelAsync();
        SelectedModelName = model is null ? "未配置模型" : model.Name;
    }

    public SessionItemViewModel CreateSessionItem(SessionRecord record) => CreateSessionItemFromRow(new SessionRow
    {
        Id = record.Id,
        Name = record.Name,
        CreatedAt = record.CreatedAt,
        UpdatedAt = record.UpdatedAt,
        TaskCount = 0
    });

    public SessionItemViewModel CreateSessionItemFromRow(SessionRow row) => new(
        row,
        loadTasks: LoadSessionTasksAsync,
        rename: async (session, name) => await _workspace.RenameSessionAsync(session.Id, name));

    public TaskItemViewModel CreateTaskItem(TaskRecord record) => new(
        record,
        _workspace.ResolveImagePath(record.ImagePath),
        new TaskItemActions
        {
            Open = ShowTaskDetailAsync,
            Retry = async item =>
            {
                var retried = await _workspace.RetryAsync(item.Id);
                if (retried is null)
                {
                    MessageBox.Show("没有可用的模型配置，请先在设置中添加模型。", "无法重试",
                        MessageBoxButton.OK, MessageBoxImage.Warning);
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
            FollowUp = RunFollowUpAsync,
            Delete = async item =>
            {
                var result = MessageBox.Show(
                    $"删除任务「{item.Title}」及其截图？此操作无法恢复。",
                    "删除任务", MessageBoxButton.OKCancel, MessageBoxImage.Warning, MessageBoxResult.Cancel);
                if (result != MessageBoxResult.OK) return;

                await _workspace.DeleteTaskAsync(item.Id);
                foreach (var session in Sessions)
                {
                    if (session.Tasks.Remove(item))
                    {
                        session.TaskCount = Math.Max(0, session.TaskCount - 1);
                        break;
                    }
                }
            }
        });

    /// <summary>展开框里的追问：把流式文本写进调用方传入的占位助手消息。</summary>
    internal async Task RunFollowUpAsync(
        TaskItemViewModel item,
        string text,
        MessageItemViewModel assistant,
        CancellationToken cancellationToken)
    {
        var record = (await _workspace.LoadTaskAsync(item.Id)) ?? item.Record;
        await _workspace.FollowUpAsync(record, text, accumulated => assistant.Content = accumulated, cancellationToken);
    }

    /// <summary>会话展开时加载其任务；正在进行的任务会恢复流式显示。</summary>
    private async Task LoadSessionTasksAsync(SessionItemViewModel session)
    {
        var tasks = await _workspace.LoadTasksAsync(session.Id);
        session.Tasks.Clear();
        foreach (var task in tasks)
        {
            session.Tasks.Add(CreateTaskItem(task));
        }
        session.TaskCount = tasks.Count;
        session.RefreshActiveState();
        OnPropertyChanged(nameof(TotalTaskCount));
    }

    /// <summary>点击任务行：打开详情页。</summary>
    /// <summary>当前在主窗口内查看的任务详情（null 表示显示会话列表）。</summary>
    [ObservableProperty]
    private TaskDetailViewModel? _detail;

    public bool IsDetailOpen => Detail is not null;

    partial void OnDetailChanged(TaskDetailViewModel? value) => OnPropertyChanged(nameof(IsDetailOpen));

    /// <summary>点击任务：在**当前窗口**打开详情，不再弹独立窗口。</summary>
    public async Task ShowTaskDetailAsync(TaskItemViewModel item)
    {
        var record = await _workspace.LoadTaskAsync(item.Id);
        if (record is null) return;

        var detail = CreateDetailViewModel(record);
        Detail = detail;
        await detail.LoadAsync();
    }

    private TaskDetailViewModel CreateDetailViewModel(TaskRecord record) =>
        new(_workspace, record)
        {
            OpenTaskRequested = async id => await ShowTaskDetailByIdAsync(id),
            CloseRequested = () => Detail = null
        };

    public async Task ShowTaskDetailByIdAsync(string taskId)
    {
        var record = await _workspace.LoadTaskAsync(taskId);
        if (record is null) return;

        var detail = CreateDetailViewModel(record);
        Detail = detail;
        await detail.LoadAsync();
    }

    [RelayCommand]
    private void CloseDetail() => Detail = null;

    public async Task OpenTaskAsync(TaskItemViewModel item) => await ShowTaskDetailAsync(item);

    /// <summary>删除会话（界面上点图标调用）。</summary>
    public async Task DeleteSessionConfirmAsync(SessionItemViewModel session)
    {
        var result = MessageBox.Show(
            $"删除会话「{session.Name}」及其中 {session.TaskCount} 个任务？截图文件也会一并删除，且无法恢复。",
            "删除会话", MessageBoxButton.OKCancel, MessageBoxImage.Warning, MessageBoxResult.Cancel);
        if (result != MessageBoxResult.OK) return;

        await _workspace.DeleteSessionAsync(session.Id);
        Sessions.Remove(session);
        EmptyHint = Sessions.Count == 0 ? "还没有会话，点击下方「开始」打开识别框。" : "";
        OnPropertyChanged(nameof(TotalTaskCount));
    }

    /// <summary>重命名会话（界面上点图标调用，弹输入框）。</summary>
    public async Task RenameSessionPromptAsync(SessionItemViewModel session)
    {
        var dialog = new ScreenAssistant.App.Views.TextInputWindow("重命名会话", session.Name)
        {
            Owner = Application.Current.MainWindow
        };
        if (dialog.ShowDialog() != true) return;

        var name = dialog.Value.Trim();
        if (name.Length == 0) return;

        session.Name = name;
        await _workspace.RenameSessionAsync(session.Id, name);
    }

    public SessionItemViewModel? FindSession(string id) =>
        Sessions.FirstOrDefault(s => s.Id == id);

    public TaskItemViewModel? FindTask(string taskId)
    {
        foreach (var session in Sessions)
        {
            var item = session.Tasks.FirstOrDefault(t => t.Id == taskId);
            if (item is not null) return item;
        }
        return null;
    }

    private void OnTaskUpdated(object? sender, TaskRecord record)
    {
        if (!Application.Current.Dispatcher.CheckAccess())
        {
            Application.Current.Dispatcher.Invoke(() => OnTaskUpdated(sender, record));
            return;
        }

        var item = FindTask(record.Id);
        if (item is null) return;

        item.Record.Title = record.Title;
        item.Record.Summary = record.Summary;
        item.Record.FirstLine = record.FirstLine;
        item.Record.Status = record.Status;
        item.Record.Error = record.Error;
        item.UpdateFromRecord();
    }

    private void OnTaskStreaming(object? sender, (string TaskId, string Accumulated) e)
    {
        if (!Application.Current.Dispatcher.CheckAccess())
        {
            Application.Current.Dispatcher.Invoke(() => OnTaskStreaming(sender, e));
            return;
        }
        FindTask(e.TaskId)?.SetStreaming(e.Accumulated);
    }

    /// <summary>新会话插入列表顶部并展开。</summary>
    public void AddSession(SessionItemViewModel session)
    {
        Sessions.Insert(0, session);
        session.IsExpanded = true;
        EmptyHint = "";
    }

    [RelayCommand]
    private async Task StartAsync()
    {
        if (StartOverlayHandler is null) return;
        var session = await StartOverlayHandler();
        if (session is null) return;

        if (FindSession(session.Id) is null) AddSession(session);
    }

    [RelayCommand]
    private async Task OpenSettingsAsync()
    {
        if (_openSettings is not null) await _openSettings();
        await RefreshModelNameAsync();
    }

    [RelayCommand]
    private async Task RefreshAsync() => await LoadAsync();
}
