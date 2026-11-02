using System.Windows;
using System.Windows.Media;

namespace ScreenAssistant.App.Themes;

/// <summary>
/// 一个主题：提供名称、显示名与整套语义化颜色令牌。
/// 新增主题只需实现本接口并注册到 <see cref="IThemeRegistry"/>，界面代码无需改动。
/// </summary>
public interface ITheme
{
    /// <summary>稳定标识（持久化用），例如 "dark" / "light"。</summary>
    string Id { get; }

    /// <summary>界面上显示的名称，例如「深色」「白色」。</summary>
    string DisplayName { get; }

    /// <summary>声明式令牌定义（XAML ResourceDictionary 的路径）。</summary>
    Uri Source { get; }

    /// <summary>设置页主题卡片的三色预览（背景 / 强调 / 文字），取自该主题的令牌。</summary>
    ThemePreview Preview { get; }
}

/// <summary>主题卡片预览用的三种颜色（十六进制字符串）。</summary>
public sealed record ThemePreview(string Background, string Accent, string Text);

/// <summary>主题注册表：按 Id 查找、枚举全部主题。</summary>
public interface IThemeRegistry
{
    IReadOnlyList<ITheme> Themes { get; }

    ITheme? Find(string? id);

    /// <summary>找不到时返回默认主题。</summary>
    ITheme Default { get; }
}

public sealed class ThemeRegistry : IThemeRegistry
{
    public static readonly ITheme Dark = new ThemeDefinition(
        "dark", "深色", new Uri("Styles/Themes/DarkTheme.xaml", UriKind.Relative),
        new ThemePreview("#17181C", "#FFB224", "#3A3D45"));

    public static readonly ITheme Light = new ThemeDefinition(
        "light", "白色", new Uri("Styles/Themes/LightTheme.xaml", UriKind.Relative),
        new ThemePreview("#F7F8FA", "#2F6FED", "#C9CFD8"));

    public IReadOnlyList<ITheme> Themes { get; } = new[] { Dark, Light };

    public ITheme Default => Dark;

    public ITheme? Find(string? id) =>
        id is null ? null : Themes.FirstOrDefault(t => string.Equals(t.Id, id, StringComparison.OrdinalIgnoreCase));

    private sealed class ThemeDefinition : ITheme
    {
        public ThemeDefinition(string id, string displayName, Uri source, ThemePreview preview)
        {
            Id = id;
            DisplayName = displayName;
            Source = source;
            Preview = preview;
        }

        public string Id { get; }
        public string DisplayName { get; }
        public Uri Source { get; }
        public ThemePreview Preview { get; }
    }
}

/// <summary>把主题令牌字典应用到整个应用（替换第一个合并字典）。</summary>
public static class ThemeApplier
{
    public static void Apply(ITheme theme)
    {
        var app = Application.Current;
        if (app is null) return;

        var dictionaries = app.Resources.MergedDictionaries;
        var tokens = new ResourceDictionary { Source = theme.Source };

        // 约定：第 0 个合并字典是主题令牌，其余是控件样式 / 图标，不随主题变化
        if (dictionaries.Count == 0)
        {
            dictionaries.Add(tokens);
            return;
        }

        dictionaries[0] = tokens;
    }
}
