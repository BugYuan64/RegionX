using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using ScreenAssistant.Core.Models;
using ScreenAssistant.Data.Repositories;

namespace ScreenAssistant.App.ViewModels;

/// <summary>主程序里的一个会话：点击后下拉展开任务列表。</summary>
public sealed partial class SessionItemViewModel : ObservableObject
{
    private readonly Func<SessionItemViewModel, Task>? _loadTasks;
    private readonly Func<SessionItemViewModel, string, Task>? _rename;
    private readonly Func<SessionItemViewModel, Task>? _delete;
    private bool _tasksLoaded;

    public SessionItemViewModel(
        SessionRow row,
        Func<SessionItemViewModel, Task>? loadTasks = null,
        Func<SessionItemViewModel, string, Task>? rename = null,
        Func<SessionItemViewModel, Task>? delete = null)
    {
        Id = row.Id;
        Name = row.Name;
        CreatedAt = row.CreatedAt;
        UpdatedAt = row.UpdatedAt;
        TaskCount = row.TaskCount;
        Record = new SessionRecord
        {
            Id = row.Id,
            Name = row.Name,
            CreatedAt = row.CreatedAt,
            UpdatedAt = row.UpdatedAt
        };
        _loadTasks = loadTasks;
        _rename = rename;
        _delete = delete;
    }

    public string Id { get; }
    public DateTimeOffset CreatedAt { get; }

    /// <summary>底层会话记录，派发任务时需要。</summary>
    public SessionRecord Record { get; }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(DisplayName))]
    private string _name;

    [ObservableProperty]
    private DateTimeOffset _updatedAt;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasActiveTask))]
    private int _taskCount;

    [ObservableProperty]
    private bool _isExpanded;

    [ObservableProperty]
    private bool _isLoadingTasks;

    [ObservableProperty]
    private bool _isEditingName;

    [ObservableProperty]
    private string _editingName = "";

    public ObservableCollection<TaskItemViewModel> Tasks { get; } = new();

    /// <summary>UI 方案里会话用「今天 22:30」这样的相对时间命名，用户重命名后显示原名。</summary>
    public string DisplayName => Name;

    /// <summary>会话里是否有任务正在处理（标题行显示「进行中」）。</summary>
    public bool HasActiveTask => Tasks.Any(t => t.IsActive);

    /// <summary>会话展开箭头的旋转角度：收起 0°，展开 90°（随状态实时更新）。</summary>
    public double ChevronAngle => IsExpanded ? 90 : 0;

    partial void OnIsExpandedChanged(bool value)
    {
        OnPropertyChanged(nameof(ChevronAngle));
        if (value && !_tasksLoaded && _loadTasks is not null)
        {
            _ = LoadTasksAsync();
        }
    }

    private async Task LoadTasksAsync()
    {
        IsLoadingTasks = true;
        try
        {
            await _loadTasks!(this);
            _tasksLoaded = true;
            OnPropertyChanged(nameof(HasActiveTask));
        }
        finally
        {
            IsLoadingTasks = false;
        }
    }

    /// <summary>新建任务后让下次展开时重新拉取；若当前已展开，立即刷新。</summary>
    public async Task InvalidateTasksAsync()
    {
        _tasksLoaded = false;
        if (IsExpanded && _loadTasks is not null) await LoadTasksAsync();
    }

    public void RefreshActiveState() => OnPropertyChanged(nameof(HasActiveTask));

    [RelayCommand]
    private void Toggle() => IsExpanded = !IsExpanded;

    [RelayCommand]
    private void BeginRename()
    {
        EditingName = Name;
        IsEditingName = true;
    }

    [RelayCommand]
    private async Task CommitRenameAsync()
    {
        var value = EditingName.Trim();
        IsEditingName = false;
        if (value.Length == 0 || value == Name) return;

        Name = value;
        if (_rename is not null) await _rename(this, value);
    }

    [RelayCommand]
    private void CancelRename() => IsEditingName = false;

    [RelayCommand]
    private async Task DeleteAsync()
    {
        if (_delete is not null) await _delete(this);
    }
}
