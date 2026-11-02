using CommunityToolkit.Mvvm.ComponentModel;
using ScreenAssistant.App.Themes;
using System.Windows.Media;

namespace ScreenAssistant.App.ViewModels;

/// <summary>设置页里的一个主题选项（含配色预览）。</summary>
public sealed partial class ThemeOption : ObservableObject
{
    public ThemeOption(ITheme theme, bool isSelected)
    {
        Id = theme.Id;
        DisplayName = theme.DisplayName;
        _isSelected = isSelected;
        // 预览色来自主题定义本身：新增主题只需给出 Preview，不用改这里
        PreviewBackground = Brush(theme.Preview.Background);
        PreviewAccent = Brush(theme.Preview.Accent);
        PreviewText = Brush(theme.Preview.Text);
    }

    public string Id { get; }
    public string DisplayName { get; }

    [ObservableProperty]
    private bool _isSelected;

    public Brush PreviewBackground { get; }
    public Brush PreviewAccent { get; }
    public Brush PreviewText { get; }

    private static SolidColorBrush Brush(string hex)
    {
        var brush = new SolidColorBrush((Color)ColorConverter.ConvertFromString(hex));
        brush.Freeze();
        return brush;
    }
}
