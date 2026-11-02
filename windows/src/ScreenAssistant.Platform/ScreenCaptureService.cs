using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Windows;
using ScreenAssistant.Core.Models;

namespace ScreenAssistant.Platform;

public sealed record CapturedRegion(byte[] Png, int X, int Y, int Width, int Height);

public interface IScreenCaptureService
{
    /// <summary>按屏幕像素矩形截取。会短暂等待界面刷新，保证识别框自身已从画面中排除。</summary>
    Task<CapturedRegion> CaptureAsync(Int32Rect pixelBounds, int delayMs, CancellationToken cancellationToken = default);
}

/// <summary>
/// Graphics.CopyFromScreen 截图。
/// 识别框 / 工具栏 / 任务队列三个窗口都设置了 WDA_EXCLUDEFROMCAPTURE，
/// 所以复制屏幕内容时不会包含这些窗口的边框，框内内容仍是桌面本身。
/// </summary>
public sealed class ScreenCaptureService : IScreenCaptureService
{
    public async Task<CapturedRegion> CaptureAsync(
        Int32Rect pixelBounds,
        int delayMs,
        CancellationToken cancellationToken = default)
    {
        // 让出 UI 线程，等一次渲染，避免刚打开的菜单 / 提示还在屏幕上
        if (delayMs > 0)
        {
            await Task.Delay(delayMs, cancellationToken).ConfigureAwait(false);
        }

        return Capture(pixelBounds);
    }

    public static CapturedRegion Capture(Int32Rect bounds)
    {
        // bounds 和 CopyFromScreen 都使用物理像素。WPF SystemParameters 返回 DIP，
        // 在 150% 缩放下会把 2560 px 的桌面当成 1706 DIP，错误裁掉右侧题目。
        var virtualLeft = NativeMethods.GetSystemMetrics(NativeMethods.SM_XVIRTUALSCREEN);
        var virtualTop = NativeMethods.GetSystemMetrics(NativeMethods.SM_YVIRTUALSCREEN);
        var virtualWidth = NativeMethods.GetSystemMetrics(NativeMethods.SM_CXVIRTUALSCREEN);
        var virtualHeight = NativeMethods.GetSystemMetrics(NativeMethods.SM_CYVIRTUALSCREEN);

        var left = Math.Max(bounds.X, virtualLeft);
        var top = Math.Max(bounds.Y, virtualTop);
        var right = Math.Min(bounds.X + bounds.Width, virtualLeft + virtualWidth);
        var bottom = Math.Min(bounds.Y + bounds.Height, virtualTop + virtualHeight);

        var width = right - left;
        var height = bottom - top;
        if (width <= 0 || height <= 0)
        {
            throw new InvalidOperationException("识别框不在可见屏幕范围内，无法截图。");
        }

        using var bitmap = new Bitmap(width, height, PixelFormat.Format32bppArgb);
        using (var graphics = Graphics.FromImage(bitmap))
        {
            graphics.CopyFromScreen(left, top, 0, 0, new System.Drawing.Size(width, height), CopyPixelOperation.SourceCopy);
        }

        using var stream = new MemoryStream();
        bitmap.Save(stream, ImageFormat.Png);
        return new CapturedRegion(stream.ToArray(), left, top, width, height);
    }

    /// <summary>把 PNG 解码为 WPF BitmapSource（按需冻结，可跨线程）。</summary>
    public static System.Windows.Media.Imaging.BitmapSource CreateBitmapSource(byte[] png)
    {
        using var stream = new MemoryStream(png);
        var bitmap = System.Windows.Media.Imaging.BitmapFrame.Create(
            stream,
            System.Windows.Media.Imaging.BitmapCreateOptions.None,
            System.Windows.Media.Imaging.BitmapCacheOption.OnLoad);
        bitmap.Freeze();
        return bitmap;
    }
}
