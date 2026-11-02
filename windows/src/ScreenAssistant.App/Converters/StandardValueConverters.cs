using System.Globalization;
using System.Windows;
using System.Windows.Data;
using ScreenAssistant.Core.Models;

namespace ScreenAssistant.App.Converters;

public sealed class EnumToTextConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture) => value switch
    {
        TaskKind kind => kind.ToDisplayName(),
        TaskState state => state.ToDisplayName(),
        LlmProvider provider => provider.ToDisplayName(),
        ChatRole role => role.ToDisplayName(),
        _ => value?.ToString() ?? ""
    };

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) =>
        Binding.DoNothing;
}

public sealed class CountToVisibilityConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        var count = value is int i ? i : 0;
        var invert = parameter is string p && p.Equals("invert", StringComparison.OrdinalIgnoreCase);
        var visible = invert ? count == 0 : count > 0;
        return visible ? Visibility.Visible : Visibility.Collapsed;
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) =>
        Binding.DoNothing;
}
