using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;

namespace ScreenAssistant.Platform;

public static class WindowHelper
{
    /// <summary>
    /// 让窗口不出现在截图里（Windows 10 2004+）。失败时返回 false，调用方可降级处理。
    /// 调试用：设置环境变量 SA_NO_CAPTURE_EXCLUDE=1 可临时关闭该行为（便于界面截图验证）。
    /// </summary>
    public static bool SetExcludeFromCapture(Window window)
    {
        if (Environment.GetEnvironmentVariable("SA_NO_CAPTURE_EXCLUDE") == "1") return false;

        var hwnd = new WindowInteropHelper(window).Handle;
        if (hwnd == IntPtr.Zero) return false;
        return NativeMethods.SetWindowDisplayAffinity(hwnd, NativeMethods.WDA_EXCLUDEFROMCAPTURE);
    }

    /// <summary>设置层级透明样式（供装饰层不接收鼠标使用）。</summary>
    public static void SetTransparentToMouse(Window window, bool transparent)
    {
        var hwnd = new WindowInteropHelper(window).Handle;
        if (hwnd == IntPtr.Zero) return;

        var exStyle = NativeMethods.GetWindowLongPtr(hwnd, NativeMethods.GWL_EXSTYLE).ToInt64();
        exStyle = transparent
            ? exStyle | NativeMethods.WS_EX_TRANSPARENT
            : exStyle & ~(long)NativeMethods.WS_EX_TRANSPARENT;
        NativeMethods.SetWindowLongPtr(hwnd, NativeMethods.GWL_EXSTYLE, new IntPtr(exStyle));
    }

    /// <summary>工具窗口样式：不出现在 Alt+Tab 和任务栏。</summary>
    public static void SetToolWindow(Window window)
    {
        var hwnd = new WindowInteropHelper(window).Handle;
        if (hwnd == IntPtr.Zero) return;
        var exStyle = NativeMethods.GetWindowLongPtr(hwnd, NativeMethods.GWL_EXSTYLE).ToInt64();
        exStyle |= NativeMethods.WS_EX_TOOLWINDOW | NativeMethods.WS_EX_NOACTIVATE;
        NativeMethods.SetWindowLongPtr(hwnd, NativeMethods.GWL_EXSTYLE, new IntPtr(exStyle));
    }

    /// <summary>窗口所在显示器的工作区（设备无关像素）。</summary>
    public static Rect GetMonitorWorkArea(Window window)
    {
        var hwnd = new WindowInteropHelper(window).Handle;
        var monitor = hwnd != IntPtr.Zero
            ? NativeMethods.MonitorFromWindow(hwnd, NativeMethods.MONITOR_DEFAULTTONEAREST)
            : IntPtr.Zero;

        if (monitor == IntPtr.Zero)
        {
            return new Rect(0, 0, SystemParameters.WorkArea.Width, SystemParameters.WorkArea.Height);
        }

        var info = new NativeMethods.MONITORINFO { cbSize = Marshal.SizeOf<NativeMethods.MONITORINFO>() };
        if (!NativeMethods.GetMonitorInfo(monitor, ref info))
        {
            return new Rect(0, 0, SystemParameters.WorkArea.Width, SystemParameters.WorkArea.Height);
        }

        var dpi = VisualTreeHelper.GetDpi(window);
        var rect = info.rcWork;
        return new Rect(
            rect.Left / dpi.DpiScaleX,
            rect.Top / dpi.DpiScaleY,
            (rect.Right - rect.Left) / dpi.DpiScaleX,
            (rect.Bottom - rect.Top) / dpi.DpiScaleY);
    }

    /// <summary>读取窗口的原生屏幕像素矩形，避免用单个 DPI 倍率缩放跨显示器的绝对坐标。</summary>
    public static Int32Rect GetPixelBounds(Window window)
    {
        var hwnd = new WindowInteropHelper(window).Handle;
        if (hwnd == IntPtr.Zero || !NativeMethods.GetWindowRect(hwnd, out var rect))
            throw new InvalidOperationException("无法读取识别框的屏幕位置，请重新打开识别框后再试。");
        return new Int32Rect(rect.Left, rect.Top, rect.Right - rect.Left, rect.Bottom - rect.Top);
    }

    public static Window? GetOwner(Window window) => window.Owner;

    public static void TrySetForeground(Window window)
    {
        var hwnd = new WindowInteropHelper(window).Handle;
        if (hwnd != IntPtr.Zero) NativeMethods.SetForegroundWindow(hwnd);
    }
}
