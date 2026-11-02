using System.Globalization;
using System.Windows;
using System.Windows.Data;
using ScreenAssistant.Core.Models;

namespace ScreenAssistant.App.Converters;

/// <summary>任务状态 → 中文文案。</summary>
public sealed class TaskStateToTextConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture) =>
        value is TaskState state ? state.ToDisplayName() : "";

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) =>
        Binding.DoNothing;
}

/// <summary>任务类型 → 图标几何资源。</summary>
public sealed class TaskKindToIconConverter : IValueConverter
{
    public object? Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        var key = value is TaskKind kind
            ? kind switch
            {
                TaskKind.Answer => "IconQuestion",
                TaskKind.Translate => "IconTranslate",
                TaskKind.Extract => "IconText",
                _ => "IconQuestion"
            }
            : "IconQuestion";

        // 与 Icon 控件不共用：这里直接反查 Geometry
        return Application.Current.TryFindResource(key) as System.Windows.Media.Geometry;
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) =>
        Binding.DoNothing;
}

/// <summary>时间 → 「今天 22:30 / 昨天 20:15 / 10月25日 14:02」。</summary>
public sealed class RelativeTimeConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        if (value is not DateTimeOffset time) return "";
        var local = time.LocalDateTime;
        var today = DateTime.Today;

        if (local.Date == today) return local.ToString("今天 HH:mm", culture);
        if (local.Date == today.AddDays(-1)) return local.ToString("昨天 HH:mm", culture);
        if (local.Year == today.Year) return local.ToString("M月d日 HH:mm", culture);
        return local.ToString("yyyy年M月d日", culture);
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) =>
        Binding.DoNothing;
}

/// <summary>非空字符串 → Visible。</summary>
public sealed class StringNotEmptyToVisibilityConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        var hasText = value is string s && s.Length > 0;
        var invert = parameter is string p && p.Equals("invert", StringComparison.OrdinalIgnoreCase);
        return hasText != invert ? Visibility.Visible : Visibility.Collapsed;
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) =>
        Binding.DoNothing;
}
