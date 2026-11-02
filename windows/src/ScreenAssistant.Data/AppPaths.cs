using ScreenAssistant.Core.Models;

namespace ScreenAssistant.Data;

/// <summary>
/// 应用数据目录。默认在 %LOCALAPPDATA%\ScreenAssistant\，
/// 若程序目录下存在可写的便携配置（regionx.portable.json），则改用程序目录，
/// 这样绿色版放在任意位置都能自包含地保存配置。
/// </summary>
public sealed class AppPaths
{
    private const string PortableMarker = "regionx.portable.json";

    public AppPaths(string? rootOverride = null)
    {
        Root = rootOverride ?? ResolveRoot();
        Images = Path.Combine(Root, "images");
        Logs = Path.Combine(Root, "logs");
        Database = Path.Combine(Root, "screenassistant.db");
        ModelsFile = Path.Combine(Root, "models.json");
        SettingsFile = Path.Combine(Root, "settings.json");
        Directory.CreateDirectory(Root);
    }

    public string Root { get; }
    public string Images { get; }
    public string Logs { get; }
    public string Database { get; }

    /// <summary>模型配置文件（密钥和附加请求头使用 DPAPI 加密）。</summary>
    public string ModelsFile { get; }
    public string SettingsFile { get; }

    /// <summary>程序所在的目录（便携模式判定用）。</summary>
    public static string AppDirectory => AppContext.BaseDirectory;

    public string EnsureImagesDirectory()
    {
        Directory.CreateDirectory(Images);
        return Images;
    }

    public string EnsureLogsDirectory()
    {
        Directory.CreateDirectory(Logs);
        return Logs;
    }

    /// <summary>把数据库里的相对图片名解析成绝对路径。</summary>
    public string ResolveImage(string? relativeOrAbsolute)
    {
        if (string.IsNullOrWhiteSpace(relativeOrAbsolute)) return "";
        return Path.IsPathRooted(relativeOrAbsolute)
            ? relativeOrAbsolute
            : Path.Combine(Images, relativeOrAbsolute);
    }

    private static string ResolveRoot()
    {
        // 1) 程序目录里有便携标记 → 直接用程序目录
        var appDir = AppDirectory;
        if (Directory.Exists(appDir) && File.Exists(Path.Combine(appDir, PortableMarker)))
        {
            return Path.Combine(appDir, "data");
        }

        // 2) 否则用 LocalAppData
        return Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "ScreenAssistant");
    }
}
