using System.Windows;
using System.ComponentModel;
using ScreenAssistant.App.Services;
using ScreenAssistant.App.ViewModels;
using ScreenAssistant.App.Views;
using ScreenAssistant.Core.Models;
using ScreenAssistant.Platform;

namespace ScreenAssistant.App;

/// <summary>
/// 识别框的生命周期：创建识别框 + 工具栏 + 任务队列，并让后两者跟随识别框。
/// 「退出」只隐藏三个窗口，数据仍保留在主程序；再次「开始」会隐藏的窗口重新挂到一个新会话上。
/// </summary>
public sealed class OverlaySessionCoordinator
{
    private readonly SettingsService _settings;
    private readonly WorkspaceService _workspace;
    private readonly IScreenCaptureService _capture;

    private OverlayWindow? _overlay;
    private ToolbarWindow? _toolbar;
    private TaskQueueWindow? _queue;
    private OverlayViewModel? _viewModel;
    private SessionItemViewModel? _session;
    private bool _tearingDown;

    public OverlaySessionCoordinator(
        SettingsService settings,
        WorkspaceService workspace,
        IScreenCaptureService capture)
    {
        _settings = settings;
        _workspace = workspace;
        _capture = capture;
    }

    public OverlayViewModel? ViewModel => _viewModel;

    public bool IsOpen => _overlay is { IsVisible: true };

    public bool HasOverlay => _overlay is not null;

    /// <summary>「开始」：新开会话，并复用（或创建）识别框。</summary>
    public async Task<SessionItemViewModel> StartNewSessionAsync(SessionRowFactory factory)
    {
        var sessionRecord = await _workspace.CreateSessionAsync();
        var sessionItem = factory(sessionRecord);

        await AttachSessionAsync(sessionItem);
        return sessionItem;
    }

    /// <summary>把识别框（重新）绑定到一个会话并显示。</summary>
    private async Task AttachSessionAsync(SessionItemViewModel sessionItem)
    {
        _session = sessionItem;

        if (_viewModel is not null)
        {
            _viewModel.PropertyChanged -= OnViewModelPropertyChanged;
            _viewModel.Detach();
        }

        _viewModel = new OverlayViewModel(
            _workspace,
            _settings,
            async (bounds, ct) => (await _capture.CaptureAsync(bounds, 0, ct)).Png)
        {
            OpenSettingsRequested = OpenSettingsAsync,
            ExitRequested = HideOverlay,
            OpenTaskDetailRequested = App.OpenTaskDetailAsync
        };

        await _viewModel.InitializeAsync(sessionItem);
        _viewModel.PropertyChanged += OnViewModelPropertyChanged;

        if (_overlay is null)
        {
            CreateWindows();
        }
        else
        {
            // 复用已隐藏的窗口：旧 ViewModel 解绑事件后再换绑
            _toolbar!.SetViewModel(_viewModel);
            _queue!.SetViewModel(_viewModel);
        }

        _toolbar!.Overlay = _overlay;
        _overlay!.RefreshHint(sessionItem.Name);
        _overlay.Show();
        _toolbar.Owner = _overlay;
        _queue!.Owner = _overlay;
        ApplyTopmost();
        _toolbar.Show();
        _queue.Show();
        _overlay.Activate();

        FollowOverlay();
    }

    private void CreateWindows()
    {
        _overlay = new OverlayWindow(_settings);
        _toolbar = new ToolbarWindow(_viewModel!, _workspace, _settings);
        _queue = new TaskQueueWindow(_viewModel!);

        _overlay.BoundsChanged += (_, _) => FollowOverlay();
        _overlay.OverlayClosed += (_, _) => { if (!_tearingDown) TearDown(closeOverlay: false); };
    }

    private void OnViewModelPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName != nameof(OverlayViewModel.IsTopmost) || !ReferenceEquals(sender, _viewModel)) return;
        ApplyTopmost();
        if (_overlay is { IsVisible: true })
        {
            _overlay.Activate();
            WindowHelper.TrySetForeground(_overlay);
            FollowOverlay();
        }
    }

    private void ApplyTopmost()
    {
        if (_viewModel is null) return;
        _overlay?.ApplyTopmost(_viewModel.IsTopmost);
        if (_toolbar is not null) _toolbar.Topmost = _viewModel.IsTopmost;
        if (_queue is not null) _queue.Topmost = _viewModel.IsTopmost;
    }

    private async Task OpenSettingsAsync()
    {
        await App.OpenSettingsAsync(_overlay);
        _viewModel?.RefreshModelName();
    }

    private void FollowOverlay()
    {
        if (_overlay is null) return;
        _toolbar?.FollowOverlay(_overlay);
        _queue?.FollowOverlay(_overlay);
    }

    /// <summary>「退出」：只隐藏，不销毁；会话与任务都保留在主程序。</summary>
    public void HideOverlay()
    {
        _overlay?.HideOverlay();
        _toolbar?.Hide();
        _queue?.Hide();
    }

    /// <summary>程序退出时真正关闭所有窗口。</summary>
    public void Shutdown()
    {
        TearDown();
    }

    private void TearDown(bool closeOverlay = true)
    {
        if (_tearingDown) return;
        _tearingDown = true;
        var overlay = _overlay;
        var toolbar = _toolbar;
        var queue = _queue;
        if (_viewModel is not null)
        {
            _viewModel.PropertyChanged -= OnViewModelPropertyChanged;
            _viewModel.Detach();
        }
        _toolbar = null;
        _queue = null;
        _overlay = null;
        _viewModel = null;
        _session = null;
        try
        {
            toolbar?.Close();
            queue?.Close();
            if (closeOverlay) overlay?.Close();
        }
        finally
        {
            _tearingDown = false;
        }
    }
}

/// <summary>由主程序提供，把会话记录包装成可绑定的条目。</summary>
public delegate SessionItemViewModel SessionRowFactory(SessionRecord record);
