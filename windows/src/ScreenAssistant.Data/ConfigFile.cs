using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace ScreenAssistant.Data;

internal static class ConfigFile
{
    // 同一目录可能被多个程序实例打开；整个读、改、写过程必须跨进程串行。
    internal static IDisposable Acquire(string path)
    {
        var identity = Encoding.UTF8.GetBytes(Path.GetFullPath(path).ToUpperInvariant());
        var mutex = new Mutex(false, @"Local\RegionX.Config." + Convert.ToHexString(SHA256.HashData(identity)));
        try
        {
            try
            {
                if (!mutex.WaitOne(TimeSpan.FromSeconds(10)))
                    throw new IOException("配置正在由另一个程序实例写入，请稍后重试。");
            }
            catch (AbandonedMutexException) { /* 上一个实例退出后，当前线程已经取得互斥锁。 */ }
            return new MutexLease(mutex);
        }
        catch { mutex.Dispose(); throw; }
    }

    private sealed class MutexLease(Mutex mutex) : IDisposable
    {
        public void Dispose()
        {
            mutex.ReleaseMutex();
            mutex.Dispose();
        }
    }
    internal static readonly JsonSerializerOptions Options = new()
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true,
        Converters = { new JsonStringEnumConverter() }
    };

    internal static T Read<T>(string path) =>
        JsonSerializer.Deserialize<T>(File.ReadAllText(path), Options)
        ?? throw new JsonException("配置文件内容为空。");

    internal static void Write<T>(string path, T value)
    {
        var temp = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            using (var stream = new FileStream(temp, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                JsonSerializer.Serialize(stream, value, Options);
                stream.Flush(flushToDisk: true);
            }
            File.Move(temp, path, overwrite: true);
        }
        finally
        {
            if (File.Exists(temp)) File.Delete(temp);
        }
    }
}
