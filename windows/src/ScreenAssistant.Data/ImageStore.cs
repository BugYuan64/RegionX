namespace ScreenAssistant.Data;

/// <summary>截图文件管理。数据库只保存相对文件名，便于整体迁移。</summary>
public interface IImageStore
{
    /// <summary>保存 PNG 字节，返回相对文件名。</summary>
    Task<string> SavePngAsync(byte[] png, CancellationToken cancellationToken = default);

    /// <summary>读取图片（相对文件名或绝对路径）；不存在时返回 null。</summary>
    Task<byte[]?> ReadAsync(string? path, CancellationToken cancellationToken = default);

    string Resolve(string? path);

    void Delete(string? relativePath);
}

public sealed class FileImageStore : IImageStore
{
    private readonly AppPaths _paths;

    public FileImageStore(AppPaths paths) => _paths = paths;

    public async Task<string> SavePngAsync(byte[] png, CancellationToken cancellationToken = default)
    {
        _paths.EnsureImagesDirectory();
        var now = DateTime.Now;
        var name = $"{now:yyyyMMdd-HHmmss}-{Guid.NewGuid().ToString("N")[..8]}.png";
        var fullPath = Path.Combine(_paths.Images, name);
        await File.WriteAllBytesAsync(fullPath, png, cancellationToken).ConfigureAwait(false);
        return name;
    }

    public async Task<byte[]?> ReadAsync(string? path, CancellationToken cancellationToken = default)
    {
        var fullPath = Resolve(path);
        if (fullPath.Length == 0 || !File.Exists(fullPath)) return null;
        return await File.ReadAllBytesAsync(fullPath, cancellationToken).ConfigureAwait(false);
    }

    public string Resolve(string? path) => _paths.ResolveImage(path);

    public void Delete(string? relativePath)
    {
        var fullPath = Resolve(relativePath);
        if (fullPath.Length == 0) return;
        try
        {
            if (File.Exists(fullPath)) File.Delete(fullPath);
        }
        catch (IOException)
        {
            // 文件被占用时忽略，下次启动可清理
        }
        catch (UnauthorizedAccessException)
        {
        }
    }
}
