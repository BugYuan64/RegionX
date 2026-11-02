using System.Windows;
using System.ComponentModel;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using ScreenAssistant.App.ViewModels;
using ScreenAssistant.Platform;

namespace ScreenAssistant.App.Views;

/// <summary>
/// 模型选择弹层：一个独立的无边框小窗口（不用系统菜单，避免白色图标栏）。
/// 选中一项后关闭；点击弹层以外区域也会关闭。
/// </summary>
public sealed class ModelPickerWindow : Window
{
    private readonly StackPanel _items = new();
    private readonly Func<Task<IReadOnlyList<ModelChoice>>> _load;
    private readonly Func<ModelChoice, Task> _select;
    private bool _isClosing;
    private bool _isClosed;

    public ModelPickerWindow(
        string currentModelName,
        Func<Task<IReadOnlyList<ModelChoice>>> load,
        Func<ModelChoice, Task> select)
    {
        _load = load;
        _select = select;
        CurrentModelName = currentModelName;

        WindowStyle = WindowStyle.None;
        AllowsTransparency = true;
        Background = Brushes.Transparent;
        ResizeMode = ResizeMode.NoResize;
        ShowInTaskbar = false;
        Topmost = true;
        SizeToContent = SizeToContent.WidthAndHeight;
        FontFamily = (FontFamily)Application.Current.Resources["UiFont"];

        var panel = new Border
        {
            Background = (Brush)Application.Current.Resources["SurfaceBrush"],
            BorderBrush = (Brush)Application.Current.Resources["BorderBrush"],
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(10),
            Padding = new Thickness(4),
            MinWidth = 190,
            MaxHeight = 320
        };
        panel.Effect = new System.Windows.Media.Effects.DropShadowEffect
        {
            BlurRadius = 20,
            ShadowDepth = 6,
            Opacity = 0.28,
            Direction = 270
        };
        panel.Child = new ScrollViewer
        {
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
            Content = _items
        };
        Content = panel;

        Deactivated += (_, _) => RequestClose();
        PreviewKeyDown += (_, e) =>
        {
            if (e.Key == Key.Escape) RequestClose();
        };

        SourceInitialized += (_, _) => WindowHelper.SetExcludeFromCapture(this);
    }

    public string CurrentModelName { get; }

    private void RequestClose()
    {
        if (!_isClosing && !_isClosed) Close();
    }

    protected override void OnClosing(CancelEventArgs e)
    {
        _isClosing = true;
        base.OnClosing(e);
        if (e.Cancel) _isClosing = false;
    }

    protected override void OnClosed(EventArgs e)
    {
        _isClosed = true;
        _owner = null;
        _anchor = null;
        base.OnClosed(e);
    }

    private Window? _owner;
    private FrameworkElement? _anchor;

    /// <summary>在指定按钮下方打开。</summary>
    public async Task ShowBelowAsync(Window owner, FrameworkElement anchor)
    {
        if (_isClosing || _isClosed || !owner.IsVisible || !anchor.IsLoaded) return;

        _owner = owner;
        _anchor = anchor;

        Show();
        if (_isClosing || _isClosed) return;
        Activate();
        if (_isClosing || _isClosed) return;
        PositionBelow(owner, anchor);
        await LoadAsync();
    }

    private void PositionBelow(Window owner, FrameworkElement anchor)
    {
        if (_isClosing || _isClosed || !IsVisible || !owner.IsVisible || !anchor.IsLoaded ||
            PresentationSource.FromVisual(anchor) is null) return;
        // PointToScreen 返回的是物理像素；换算成 DIP 后再赋值给 Left/Top
        var point = anchor.PointToScreen(new Point(0, anchor.ActualHeight + 6));
        var dpi = VisualTreeHelper.GetDpi(anchor);
        Left = point.X / dpi.DpiScaleX;
        Top = point.Y / dpi.DpiScaleY;

        // 超出屏幕右边缘时向左收
        var area = WindowHelper.GetMonitorWorkArea(owner);
        if (Left + ActualWidth > area.Right - 4)
        {
            Left = Math.Max(area.Left + 4, area.Right - ActualWidth - 4);
        }
    }

    private async Task LoadAsync()
    {
        var loading = CreateText("正在读取已保存模型…");
        _items.Children.Clear();
        _items.Children.Add(loading);

        IReadOnlyList<ModelChoice> models;
        try
        {
            models = await _load();
        }
        catch (Exception ex)
        {
            if (_isClosing || _isClosed) return;
            _items.Children.Clear();
            _items.Children.Add(CreateText($"获取失败：{ex.Message}"));
            return;
        }

        if (_isClosing || _isClosed || !IsVisible) return;
        _items.Children.Clear();
        if (models.Count == 0)
        {
            _items.Children.Add(CreateText("没有可用模型，请在设置中配置"));
            return;
        }

        foreach (var choice in models)
        {
            _items.Children.Add(CreateRow(choice));
        }

        _items.Children.Add(new Border
        {
            Height = 1,
            Margin = new Thickness(6, 4, 6, 4),
            Background = (Brush)Application.Current.Resources["BorderBrush"]
        });
        _items.Children.Add(CreateSettingsRow());

        // 内容变化后重新定位，避免高度变化导致错位
        UpdateLayout();
        if (_anchor is not null && _owner is not null) PositionBelow(_owner, _anchor);
    }

    private Border CreateRow(ModelChoice choice)
    {
        // 左侧品牌图标随模型名匹配；当前在用的配置整行用强调色，并在右侧打勾
        var foreground = choice.IsSelected
            ? (Brush)Application.Current.Resources["AccentBrush"]
            : (Brush)Application.Current.Resources["TextPrimaryBrush"];

        var icon = new Controls.Icon
        {
            FillIcon = true,
            Thickness = 0,
            Data = Converters.BrandIconConverter.ResolveGeometry(choice.ModelName),
            Size = 15,
            Foreground = foreground,
            VerticalAlignment = VerticalAlignment.Center
        };

        var textPanel = new StackPanel
        {
            Orientation = Orientation.Vertical,
            Margin = new Thickness(8, 0, 0, 0),
            VerticalAlignment = VerticalAlignment.Center
        };

        textPanel.Children.Add(new TextBlock
        {
            Text = choice.ProviderName,
            VerticalAlignment = VerticalAlignment.Center,
            Foreground = foreground,
            TextTrimming = TextTrimming.CharacterEllipsis
        });

        // 配置名就是模型名时不重复显示
        if (choice.SubText is { Length: > 0 } sub)
        {
            textPanel.Children.Add(new TextBlock
            {
                Text = sub,
                FontSize = 11,
                Foreground = (Brush)Application.Current.Resources["TextSecondaryBrush"],
                TextTrimming = TextTrimming.CharacterEllipsis
            });
        }

        var panel = new Grid();
        panel.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        panel.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        panel.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        Grid.SetColumn(icon, 0);
        Grid.SetColumn(textPanel, 1);
        panel.Children.Add(icon);
        panel.Children.Add(textPanel);

        // 当前选中：右侧对勾（否则留空，保证各项文字左边对齐）
        var check = new Controls.Icon
        {
            Data = (Geometry?)Application.Current.Resources["IconCheck"],
            Size = 14,
            Thickness = 2,
            Foreground = (Brush)Application.Current.Resources["AccentBrush"],
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(10, 0, 0, 0),
            Visibility = choice.IsSelected ? Visibility.Visible : Visibility.Hidden
        };
        Grid.SetColumn(check, 2);
        panel.Children.Add(check);

        var row = new Border
        {
            Background = Brushes.Transparent,
            CornerRadius = new CornerRadius(6),
            Padding = new Thickness(10, 7, 10, 7),
            Margin = new Thickness(2, 1, 2, 1),
            MinWidth = 200,
            Cursor = Cursors.Hand,
            Child = panel
        };

        row.MouseEnter += (_, _) => row.Background = (Brush)Application.Current.Resources["SurfaceHoverBrush"];
        row.MouseLeave += (_, _) => row.Background = Brushes.Transparent;
        row.MouseLeftButtonUp += async (_, _) =>
        {
            RequestClose();
            await _select(choice);
        };

        return row;
    }

    private Border CreateSettingsRow()
    {
        var text = new TextBlock
        {
            Text = "在设置中配置模型…",
            FontSize = 12,
            Foreground = (Brush)Application.Current.Resources["TextSecondaryBrush"]
        };

        var row = new Border
        {
            Background = Brushes.Transparent,
            CornerRadius = new CornerRadius(6),
            Padding = new Thickness(10, 6, 10, 6),
            Margin = new Thickness(2, 1, 2, 1),
            Cursor = Cursors.Hand,
            Child = text
        };
        row.MouseEnter += (_, _) => row.Background = (Brush)Application.Current.Resources["SurfaceHoverBrush"];
        row.MouseLeave += (_, _) => row.Background = Brushes.Transparent;
        row.MouseLeftButtonUp += async (_, _) =>
        {
            RequestClose();
            await App.OpenSettingsAsync(Application.Current.MainWindow);
        };
        return row;
    }

    private static TextBlock CreateText(string text) => new()
    {
        Text = text,
        FontSize = 12,
        Margin = new Thickness(10, 8, 10, 8),
        Foreground = (Brush)Application.Current.Resources["TextSecondaryBrush"]
    };
}

