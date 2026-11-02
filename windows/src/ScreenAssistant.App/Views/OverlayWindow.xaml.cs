using System.Text.Json;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using ScreenAssistant.App.Services;
using ScreenAssistant.Platform;

namespace ScreenAssistant.App.Views;

/// <summary>
/// 透明识别框。只有边框和手柄接收鼠标，框内点击穿透到桌面。
/// 隐藏不销毁窗口；点「退出」才真正关闭。
/// </summary>
public partial class OverlayWindow : Window
{
    public const double MinOverlayWidth = 160;
    public const double MinOverlayHeight = 120;

    private enum DragMode { None, Move, Resize }

    private readonly SettingsService _settings;

    private DragMode _mode = DragMode.None;
    private Point _dragStartScreen;
    private Rect _startBounds;
    private string _resizeHandle = "";
    private bool _positioned;

    public OverlayWindow(SettingsService settings)
    {
        _settings = settings;
        InitializeComponent();

        SourceInitialized += OnSourceInitialized;
        Loaded += OnLoaded;
        SizeChanged += (_, _) => RaiseBoundsChanged();
        LocationChanged += (_, _) => RaiseBoundsChanged();
    }

    /// <summary>位置或大小变化（工具栏与任务队列据此跟随）。</summary>
    public event EventHandler? BoundsChanged;

    public event EventHandler? OverlayClosed;

    public bool IsHiding { get; private set; }

    /// <summary>标识当前识别框实例；新开会话时用于比较。</summary>
    public string InstanceId { get; } = Guid.NewGuid().ToString("N");

    private void OnSourceInitialized(object? sender, EventArgs e)
    {
        // 截图排除自身：识别框、工具栏、任务队列都不出现在截图里。
        // 注意：识别框是独立窗口（进任务栏），不能设置工具窗口样式。
        WindowHelper.SetExcludeFromCapture(this);

        if (_settings.Settings.OverlayBounds is { Length: > 0 } raw)
        {
            TryRestoreBounds(raw);
        }
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        if (!_positioned)
        {
            CenterOnScreen();
            _positioned = true;
        }
    }

    private void CenterOnScreen()
    {
        var area = WindowHelper.GetMonitorWorkArea(this);
        Left = Math.Round(area.Left + (area.Width - Width) / 2);
        Top = Math.Round(area.Top + (area.Height - Height) / 2);
    }

    private void TryRestoreBounds(string raw)
    {
        try
        {
            var values = JsonSerializer.Deserialize<double[]>(raw);
            if (values is { Length: 4 } && values[2] >= MinOverlayWidth && values[3] >= MinOverlayHeight)
            {
                Width = values[2];
                Height = values[3];
                Left = values[0];
                Top = values[1];
                _positioned = true;
                EnsureVisible();
            }
        }
        catch (JsonException)
        {
        }
    }

    private void EnsureVisible()
    {
        var left = SystemParameters.VirtualScreenLeft;
        var top = SystemParameters.VirtualScreenTop;
        var right = left + SystemParameters.VirtualScreenWidth;
        var bottom = top + SystemParameters.VirtualScreenHeight;

        var sufficientlyVisible = Left + Width > left + 60 && Left < right - 60
                                  && Top + Height > top + 40 && Top < bottom - 40;
        if (!sufficientlyVisible)
        {
            CenterOnScreen();
        }
    }

    private void RaiseBoundsChanged()
    {
        BoundsChanged?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>截图用的屏幕像素矩形。</summary>
    public Int32Rect GetPixelBounds() => WindowHelper.GetPixelBounds(this);

    public string SerializeBounds() => JsonSerializer.Serialize(new[] { Left, Top, ActualWidth, ActualHeight });

    private void Frame_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        BeginDrag(DragMode.Move);
        e.Handled = true;
    }

    private void Handle_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (sender is FrameworkElement { Tag: string tag })
        {
            _resizeHandle = tag;
            BeginDrag(DragMode.Resize);
            e.Handled = true;
        }
    }

    private void BeginDrag(DragMode mode)
    {
        _mode = mode;
        _dragStartScreen = GetCursorInDips();
        _startBounds = new Rect(Left, Top, ActualWidth, ActualHeight);
        Mouse.Capture(RootGrid, CaptureMode.SubTree);
        RootGrid.MouseMove += RootGrid_MouseMove;
        RootGrid.MouseLeftButtonUp += RootGrid_MouseLeftButtonUp;

        // 操作时显示尺寸标签（UI 方案里悬浮在边框上方）
        SizeLabel.Visibility = Visibility.Visible;
        UpdateSizeLabel();
    }

    private void UpdateSizeLabel() =>
        SizeLabelText.Text = $"识别框 {Math.Round(ActualWidth)} × {Math.Round(ActualHeight)}";

    private void RootGrid_MouseMove(object sender, MouseEventArgs e)
    {
        if (_mode == DragMode.None) return;
        if (e.LeftButton != MouseButtonState.Pressed)
        {
            EndDrag();
            return;
        }
        Drag();
    }

    private void RootGrid_MouseLeftButtonUp(object sender, MouseButtonEventArgs e) => EndDrag();

    private void EndDrag()
    {
        if (_mode == DragMode.None) return;
        _mode = DragMode.None;
        RootGrid.MouseMove -= RootGrid_MouseMove;
        RootGrid.MouseLeftButtonUp -= RootGrid_MouseLeftButtonUp;
        Mouse.Capture(null);
        SizeLabel.Visibility = Visibility.Collapsed;
        RaiseBoundsChanged();

        // 拖动结束后立即记下位置，避免程序异常退出时丢失
        SaveBounds();
    }

    private Point GetCursorInDips()
    {
        var screenPoint = PointToScreen(Mouse.GetPosition(this));
        var dpi = VisualTreeHelper.GetDpi(this);
        return new Point(screenPoint.X / dpi.DpiScaleX, screenPoint.Y / dpi.DpiScaleY);
    }

    private void Drag()
    {
        var current = GetCursorInDips();
        var dx = current.X - _dragStartScreen.X;
        var dy = current.Y - _dragStartScreen.Y;

        if (_mode == DragMode.Move)
        {
            Left = Math.Round(_startBounds.Left + dx);
            Top = Math.Round(_startBounds.Top + dy);
            return;
        }

        Resize(dx, dy);
    }

    private void Resize(double dx, double dy)
    {
        var left = _startBounds.Left;
        var top = _startBounds.Top;
        var right = _startBounds.Right;
        var bottom = _startBounds.Bottom;

        if (_resizeHandle.Contains('W')) left = Math.Min(left + dx, right - MinOverlayWidth);
        if (_resizeHandle.Contains('E')) right = Math.Max(right + dx, left + MinOverlayWidth);
        if (_resizeHandle.Contains('N')) top = Math.Min(top + dy, bottom - MinOverlayHeight);
        if (_resizeHandle.Contains('S')) bottom = Math.Max(bottom + dy, top + MinOverlayHeight);

        Left = Math.Round(left);
        Top = Math.Round(top);
        Width = Math.Round(right - left);
        Height = Math.Round(bottom - top);

        SnapToScreenEdges();
        UpdateSizeLabel();
    }

    private void SnapToScreenEdges()
    {
        const double threshold = 8;
        var area = WindowHelper.GetMonitorWorkArea(this);

        if (Math.Abs(Left - area.Left) < threshold) Left = area.Left;
        if (Math.Abs(Top - area.Top) < threshold) Top = area.Top;
        if (Math.Abs(Left + Width - area.Right) < threshold) Left = area.Right - Width;
        if (Math.Abs(Top + Height - area.Bottom) < threshold) Top = area.Bottom - Height;
    }

    /// <summary>隐藏而不关闭：任务队列与会话都保留。隐藏前记住位置，下次「开始」沿用。</summary>
    public void HideOverlay()
    {
        SaveBounds();
        IsHiding = true;
        Hide();
        IsHiding = false;
    }

    public void ApplyTopmost(bool topmost) => Topmost = topmost;

    /// <summary>在边框角落显示当前会话名，便于确认「开始」确实开了新会话。</summary>
    public void RefreshHint(string sessionName)
    {
        Title = $"识别框 · {sessionName}";
    }

    protected override void OnClosed(EventArgs e)
    {
        SaveBounds();
        OverlayClosed?.Invoke(this, EventArgs.Empty);
        base.OnClosed(e);
    }

    private void SaveBounds()
    {
        try
        {
            _settings.Update(s => s.OverlayBounds = SerializeBounds());
        }
        catch (Exception)
        {
            // 退出阶段不因保存失败中断
        }
    }
}
