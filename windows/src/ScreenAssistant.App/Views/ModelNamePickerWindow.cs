using System.Collections.Generic;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using ScreenAssistant.App.ViewModels;
using ScreenAssistant.Platform;

namespace ScreenAssistant.App.Views;

/// <summary>
/// 从上游模型列表里挑一个模型名。
/// 用独立小窗口而不是下拉框：下拉框在无边框窗口里的弹出层不稳定，
/// 这里与识别框的模型菜单保持一致的交互（点一项即选中并关闭）。
/// </summary>
public sealed class ModelNamePickerWindow : Window
{
    private readonly StackPanel _items = new();

    public ModelNamePickerWindow(string? current, IReadOnlyList<string> models)
    {
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
            MinWidth = 220,
            MaxHeight = 340
        };
        panel.Effect = new System.Windows.Media.Effects.DropShadowEffect
        {
            BlurRadius = 20, ShadowDepth = 6, Opacity = 0.28, Direction = 270
        };
        panel.Child = new ScrollViewer
        {
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
            Content = _items
        };
        Content = panel;

        foreach (var name in models) _items.Children.Add(CreateRow(name, current));

        // 模态小窗：按 Esc 关闭（点外部由 ShowDialog 拦截，不能靠 Deactivated，
        // 否则窗口一显示就会因为失去激活而立刻关闭）
        PreviewKeyDown += (_, e) =>
        {
            if (e.Key == Key.Escape) Close();
        };

        SourceInitialized += (_, _) => WindowHelper.SetExcludeFromCapture(this);
    }

    /// <summary>用户选中的模型名（取消时为 null）。</summary>
    public string? Selected { get; private set; }

    private Border CreateRow(string name, string? current)
    {
        var isCurrent = string.Equals(name, current, StringComparison.OrdinalIgnoreCase);
        var foreground = isCurrent
            ? (Brush)Application.Current.Resources["AccentBrush"]
            : (Brush)Application.Current.Resources["TextPrimaryBrush"];

        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        // 左侧品牌图标
        var icon = new Controls.Icon
        {
            FillIcon = true,
            Thickness = 0,
            Data = Converters.BrandIconConverter.ResolveGeometry(name),
            Size = 15,
            Foreground = foreground,
            VerticalAlignment = VerticalAlignment.Center
        };
        Grid.SetColumn(icon, 0);

        var text = new TextBlock
        {
            Text = name,
            Margin = new Thickness(8, 0, 0, 0),
            VerticalAlignment = VerticalAlignment.Center,
            Foreground = foreground,
            TextTrimming = TextTrimming.CharacterEllipsis
        };
        Grid.SetColumn(text, 1);

        // 当前项右侧打勾
        var check = new Controls.Icon
        {
            Data = (Geometry?)Application.Current.Resources["IconCheck"],
            Size = 14,
            Thickness = 2,
            Foreground = foreground,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(10, 0, 0, 0),
            Visibility = isCurrent ? Visibility.Visible : Visibility.Hidden
        };
        Grid.SetColumn(check, 2);

        grid.Children.Add(icon);
        grid.Children.Add(text);
        grid.Children.Add(check);

        var row = new Border
        {
            Background = Brushes.Transparent,
            CornerRadius = new CornerRadius(6),
            Padding = new Thickness(10, 7, 10, 7),
            Margin = new Thickness(2, 1, 2, 1),
            Cursor = Cursors.Hand,
            Child = grid
        };

        row.MouseEnter += (_, _) => row.Background = (Brush)Application.Current.Resources["SurfaceHoverBrush"];
        row.MouseLeave += (_, _) => row.Background = Brushes.Transparent;
        row.MouseLeftButtonUp += (_, _) =>
        {
            Selected = name;
            DialogResult = true;
        };

        return row;
    }
}

