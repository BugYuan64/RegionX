using System.Windows;
using System.Windows.Input;
using System.Windows.Interop;

namespace ScreenAssistant.Platform;

public enum HotkeyAction
{
    ToggleOverlay = 1,
    CaptureAnswer = 2,
    CaptureTranslate = 3,
    CaptureExtract = 4
}

/// <summary>全局快捷键，用 RegisterHotKey 注册在指定窗口上。</summary>
public sealed class HotkeyManager : IDisposable
{
    private readonly HwndSource? _source;
    private readonly Dictionary<int, HotkeyAction> _registered = new();

    public event Action<HotkeyAction>? Pressed;

    public HotkeyManager(Window window)
    {
        var handle = new WindowInteropHelper(window).Handle;
        _source = HwndSource.FromHwnd(handle);
        _source?.AddHook(WndProc);
    }

    public bool Register(HotkeyAction action, ModifierKeys modifiers, Key key)
    {
        if (_source is null) return false;

        var id = 0x5000 + (int)action;
        Unregister(action);

        var mods = 0u;
        if (modifiers.HasFlag(ModifierKeys.Alt)) mods |= NativeMethods.MOD_ALT;
        if (modifiers.HasFlag(ModifierKeys.Control)) mods |= NativeMethods.MOD_CONTROL;
        if (modifiers.HasFlag(ModifierKeys.Shift)) mods |= NativeMethods.MOD_SHIFT;
        if (modifiers.HasFlag(ModifierKeys.Windows)) mods |= NativeMethods.MOD_WIN;

        var vk = (uint)System.Windows.Input.KeyInterop.VirtualKeyFromKey(key);
        if (!NativeMethods.RegisterHotKey(_source.Handle, id, mods, vk)) return false;

        _registered[id] = action;
        return true;
    }

    public void Unregister(HotkeyAction action)
    {
        var id = 0x5000 + (int)action;
        if (_registered.Remove(id) && _source is not null)
        {
            NativeMethods.UnregisterHotKey(_source.Handle, id);
        }
    }

    private IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg == NativeMethods.WM_HOTKEY && _registered.TryGetValue(wParam.ToInt32(), out var action))
        {
            Pressed?.Invoke(action);
            handled = true;
        }
        return IntPtr.Zero;
    }

    public void Dispose()
    {
        foreach (var id in _registered.Keys.ToList())
        {
            if (_source is not null) NativeMethods.UnregisterHotKey(_source.Handle, id);
        }
        _registered.Clear();
        _source?.RemoveHook(WndProc);
    }
}
