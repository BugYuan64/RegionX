using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using ScreenAssistant.Platform;

namespace ScreenAssistant.Tests;

[Collection("WPF")]
public sealed class ScreenCaptureTests
{
    [Fact]
    public Task Capture_RightSideOfPhysicalDesktop_PreservesFullPixelWidth() => WpfTestHost.RunAsync(() =>
    {
        var left = GetSystemMetrics(76);
        var top = GetSystemMetrics(77);
        var width = GetSystemMetrics(78);
        var height = GetSystemMetrics(79);
        var requestedWidth = Math.Min(400, width / 4);
        var requestedHeight = Math.Min(200, height / 4);
        // 选取物理桌面最右侧；缩放屏幕中该区域可能已超过 WPF 的逻辑桌面宽度。
        var bounds = new Int32Rect(left + width - requestedWidth, top + height / 3, requestedWidth, requestedHeight);
        var capture = ScreenCaptureService.Capture(bounds);
        Assert.Equal(bounds.Width, capture.Width);
        Assert.Equal(bounds.Height, capture.Height);
        using var stream = new MemoryStream(capture.Png);
        var bitmap = BitmapFrame.Create(stream);
        Assert.Equal(bounds.Width, bitmap.PixelWidth);
        Assert.Equal(bounds.Height, bitmap.PixelHeight);
        return Task.CompletedTask;
    });

    [Fact]
    public Task Capture_UserReportedRegion_DoesNotLoseQuestionArea() => WpfTestHost.RunAsync(() =>
    {
        var right = GetSystemMetrics(76) + GetSystemMetrics(78);
        var bottom = GetSystemMetrics(77) + GetSystemMetrics(79);
        // 用户失败任务：请求 894×446，旧代码实际只保存 131×440。
        var bounds = new Int32Rect(1575, 626, 894, 446);
        if (right < bounds.X + bounds.Width || bottom < bounds.Y + bounds.Height)
            return Task.CompletedTask; // 本机桌面不容纳该历史区域时由上面的自适应测试覆盖。
        var capture = ScreenCaptureService.Capture(bounds);
        Assert.Equal((894, 446), (capture.Width, capture.Height));
        return Task.CompletedTask;
    });

    [Fact]
    public Task GetPixelBounds_MatchesNativeWindowRectangle() => WpfTestHost.RunAsync(() =>
    {
        var window = new Window
        {
            WindowStyle = WindowStyle.None, Width = 300, Height = 160,
            Left = 200, Top = 120, ShowInTaskbar = false, Background = Brushes.White
        };
        try
        {
            window.Show();
            Assert.True(GetWindowRect(new WindowInteropHelper(window).Handle, out var native));
            Assert.Equal(new Int32Rect(native.Left, native.Top, native.Right - native.Left, native.Bottom - native.Top),
                WindowHelper.GetPixelBounds(window));
        }
        finally { window.Close(); }
        return Task.CompletedTask;
    });

    [DllImport("user32.dll")]
    private static extern int GetSystemMetrics(int metric);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetWindowRect(IntPtr hwnd, out RectPixels rect);

    [StructLayout(LayoutKind.Sequential)]
    private struct RectPixels { public int Left, Top, Right, Bottom; }
}
