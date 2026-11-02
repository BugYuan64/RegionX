using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using ScreenAssistant.App.Services;
using ScreenAssistant.App.ViewModels;
using ScreenAssistant.Core.Models;
using ScreenAssistant.Platform;

namespace ScreenAssistant.App.Views;

/// <summary>贴在识别框外侧下方的工具栏（6 个按钮）。</summary>
public partial class ToolbarWindow : Window
{
    private readonly WorkspaceService _workspace;
    private readonly SettingsService _settings;

    private OverlayViewModel _viewModel;
    private ModelPickerWindow? _modelPicker;
    private bool _isClosed;

    /// <summary>截图区域来自识别框；窗口归属于同一个识别框，保持层级一致。</summary>
    public OverlayWindow? Overlay { get; set; }

    public ToolbarWindow(OverlayViewModel viewModel, WorkspaceService workspace, SettingsService settings)
    {
        InitializeComponent();
        _viewModel = viewModel;
        _workspace = workspace;
        _settings = settings;
        DataContext = viewModel;

        SourceInitialized += (_, _) =>
        {
            WindowHelper.SetExcludeFromCapture(this);
            WindowHelper.SetToolWindow(this);
        };

        viewModel.PropertyChanged += ViewModel_PropertyChanged;
        Loaded += (_, _) => UpdateLabels();

    }

    public OverlayViewModel ViewModel => _viewModel;

    /// <summary>识别框复用到新会话时换绑 ViewModel。</summary>
    public void SetViewModel(OverlayViewModel viewModel)
    {
        if (ReferenceEquals(_viewModel, viewModel)) return;
        _viewModel.PropertyChanged -= ViewModel_PropertyChanged;
        _viewModel = viewModel;
        DataContext = viewModel;
        viewModel.PropertyChanged += ViewModel_PropertyChanged;
        UpdateLabels();
    }

    private void ViewModel_PropertyChanged(object? sender, System.ComponentModel.PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(OverlayViewModel.ModelName) or nameof(OverlayViewModel.IsTopmost))
        {
            UpdateLabels();
        }
    }

    private void UpdateLabels()
    {
        var name = _viewModel.ModelName;
        ModelButtonText.Text = name.Length > 10 ? name[..10] + "…" : name;
        ModelButton.ToolTip = $"当前模型：{name}（点击选择）";

        var topmost = _viewModel.IsTopmost;
        TopmostIcon.Foreground = topmost
            ? (System.Windows.Media.Brush)FindResource("AccentBrush")
            : (System.Windows.Media.Brush)FindResource("TextSecondaryBrush");
        TopmostText.Foreground = TopmostIcon.Foreground;
        TopmostText.Text = topmost ? "已置顶" : "置顶";
    }

    /// <summary>由识别框位置驱动；靠近下边缘时翻到上方。</summary>
    public void FollowOverlay(OverlayWindow overlay)
    {
        if (!IsLoaded) return;

        const double gap = 8;
        var area = WindowHelper.GetMonitorWorkArea(overlay);
        var targetLeft = overlay.Left + (overlay.ActualWidth - ActualWidth) / 2;
        targetLeft = Math.Max(area.Left + 4, Math.Min(targetLeft, area.Right - ActualWidth - 4));

        var below = overlay.Top + overlay.ActualHeight + gap;
        var above = overlay.Top - ActualHeight - gap;

        var targetTop = below + ActualHeight <= area.Bottom - 4
            ? below
            : above >= area.Top + 4
                ? above
                : Math.Min(below, area.Bottom - ActualHeight - 4);

        Left = targetLeft;
        Top = targetTop;
    }

    private async void Answer_Click(object sender, RoutedEventArgs e) => await CaptureAsync(TaskKind.Answer);
    private async void Translate_Click(object sender, RoutedEventArgs e) => await CaptureAsync(TaskKind.Translate);
    private async void Extract_Click(object sender, RoutedEventArgs e) => await CaptureAsync(TaskKind.Extract);

    private async Task CaptureAsync(TaskKind kind)
    {
        if (Overlay is null)
        {
            _viewModel.StatusMessage = "识别框尚未就绪，请稍后重试。";
            return;
        }

        var bounds = Overlay.GetPixelBounds();
        var delay = _settings.Settings.CaptureDelayMs;
        try
        {
            await _viewModel.CaptureAndQueueAsync(kind, bounds, delay);
        }
        catch (Exception ex)
        {
            _viewModel.StatusMessage = $"操作失败：{ex.Message}";
        }
    }

    /// <summary>「模型」按钮：弹出独立的模型选择小窗（不用系统菜单，避免白色图标栏）。</summary>
    private async void Model_Click(object sender, RoutedEventArgs e)
    {
        if (_modelPicker is { IsVisible: true })
        {
            _modelPicker.Close();
            return;
        }
        var picker = new ModelPickerWindow(
            _viewModel.ModelName,
            _viewModel.LoadSelectableModelsAsync,
            async choice =>
            {
                try
                {
                    await _viewModel.SelectModelAsync(choice);
                    if (!_isClosed) UpdateLabels();
                }
                catch (Exception ex) { _viewModel.StatusMessage = $"切换模型失败：{ex.Message}"; }
            });

        _modelPicker = picker;
        picker.Closed += (_, _) => { if (ReferenceEquals(_modelPicker, picker)) _modelPicker = null; };
        picker.Owner = this;
        try { await picker.ShowBelowAsync(this, ModelButton); }
        catch (Exception ex) { if (!_isClosed) _viewModel.StatusMessage = $"打开模型列表失败：{ex.Message}"; }
    }

    protected override void OnClosed(EventArgs e)
    {
        _isClosed = true;
        _viewModel.PropertyChanged -= ViewModel_PropertyChanged;
        base.OnClosed(e);
    }

    private void Topmost_Click(object sender, RoutedEventArgs e)
    {
        _viewModel.ToggleTopmostCommand.Execute(null);
        UpdateLabels();
    }

    private void Exit_Click(object sender, RoutedEventArgs e) => _viewModel.ExitCommand.Execute(null);
}
