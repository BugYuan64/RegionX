param([int]$ProcId, [string]$Title)
Add-Type @'
using System; using System.Runtime.InteropServices;
public static class FG {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  public delegate bool P(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(P p, IntPtr l);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, System.Text.StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
  [DllImport("user32.dll")] public static extern IntPtr SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern void keybd_event(byte b, byte s, uint f, UIntPtr e);
  public static string Run(uint pid, string title) {
    IntPtr found = IntPtr.Zero;
    P cb = (h,l) => {
      uint p; GetWindowThreadProcessId(h, out p);
      if (p != pid || !IsWindowVisible(h)) return true;
      var t = new System.Text.StringBuilder(256); GetWindowText(h, t, 256);
      if (t.ToString() == title) { found = h; return false; }
      return true;
    };
    EnumWindows(cb, IntPtr.Zero); GC.KeepAlive(cb);
    if (found == IntPtr.Zero) return "NOT_FOUND";
    SetProcessDPIAware();
    ShowWindow(found, 9);
    keybd_event(0x12, 0, 0, UIntPtr.Zero); keybd_event(0x12, 0, 2, UIntPtr.Zero);
    SetForegroundWindow(found);
    return "FRONT " + title;
  }
}
'@
[FG]::Run([uint32]$ProcId, $Title)
