using System.IO;
using System.Globalization;
using System.Windows;
using System.Windows.Data;
using System.Windows.Media;
using ScreenAssistant.Core.Models;

namespace ScreenAssistant.App.Converters;

public sealed class BoolToVisibilityConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        var flag = value is bool b && b;
        if (parameter is string s && s.Equals("invert", StringComparison.OrdinalIgnoreCase)) flag = !flag;
        return flag ? Visibility.Visible : Visibility.Collapsed;
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) =>
        value is Visibility.Visible;
}

public sealed class InverseBoolConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture) =>
        value is not bool b || !b;

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) =>
        value is not bool b || !b;
}

public sealed class NullToVisibilityConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        var visible = value is not null && (value is not string s || s.Length > 0);
        if (parameter is string p && p.Equals("invert", StringComparison.OrdinalIgnoreCase)) visible = !visible;
        return visible ? Visibility.Visible : Visibility.Collapsed;
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) =>
        Binding.DoNothing;
}

public sealed class TaskStateToBrushConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        var key = value is TaskState state
            ? state switch
            {
                TaskState.Queued => "PendingBrush",
                TaskState.Running => "AccentBrush",
                TaskState.Completed => "SuccessBrush",
                TaskState.Failed => "DangerBrush",
                TaskState.Cancelled => "WarningBrush",
                _ => "PendingBrush"
            }
            : "PendingBrush";

        return Application.Current.TryFindResource(key) as Brush ?? Brushes.Gray;
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) =>
        Binding.DoNothing;
}

/// <summary>状态对应的浅色底（用主题里的 *SoftBrush 令牌，随主题切换）。</summary>
public sealed class TaskStateToSoftBrushConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        var key = value is TaskState state
            ? state switch
            {
                TaskState.Running => "AccentSoftBrush",
                TaskState.Completed => "SuccessSoftBrush",
                TaskState.Failed => "DangerSoftBrush",
                TaskState.Cancelled => "WarningSoftBrush",
                _ => "SurfaceAltBrush"
            }
            : "SurfaceAltBrush";

        return Application.Current.TryFindResource(key) as Brush ?? Brushes.Transparent;
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) =>
        Binding.DoNothing;
}

public sealed class TimestampConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        if (value is not DateTimeOffset time) return "";
        var local = time.LocalDateTime;
        var pattern = parameter as string ?? "MM-dd HH:mm";
        return local.Date == DateTime.Today
            ? local.ToString("HH:mm", culture)
            : local.ToString(pattern, culture);
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) =>
        Binding.DoNothing;
}

/// <summary>把路径字符串转成图片源（带缓存，流式刷新时不会反复读盘）。</summary>
public sealed class PathToImageConverter : IValueConverter
{
    private static readonly Dictionary<string, (DateTime WriteTime, ImageSource Source)> Cache = new();

    public object? Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        if (value is not string path || path.Length == 0 || !File.Exists(path)) return null;

        var writeTime = File.GetLastWriteTimeUtc(path);
        lock (Cache)
        {
            if (Cache.TryGetValue(path, out var entry) && entry.WriteTime == writeTime)
            {
                return entry.Source;
            }
        }

        try
        {
            var image = new System.Windows.Media.Imaging.BitmapImage();
            image.BeginInit();
            image.CacheOption = System.Windows.Media.Imaging.BitmapCacheOption.OnLoad;
            image.CreateOptions = System.Windows.Media.Imaging.BitmapCreateOptions.IgnoreImageCache;
            image.UriSource = new Uri(path);
            image.EndInit();
            image.Freeze();

            lock (Cache)
            {
                Cache[path] = (writeTime, image);
                if (Cache.Count > 100)
                {
                    // 简单上限，避免长时间运行缓存无限增长
                    foreach (var key in Cache.Keys.Take(Cache.Count - 100).ToList()) Cache.Remove(key);
                }
            }
            return image;
        }
        catch (Exception)
        {
            return null;
        }
    }

    public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) =>
        Binding.DoNothing;
}
