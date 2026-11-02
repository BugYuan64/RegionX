param([int]$X, [int]$Y)
Add-Type @'
using System; using System.Runtime.InteropServices;
public static class M {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f,uint x,uint y,uint d,IntPtr e);
  public static void Click(int x, int y) {
    SetProcessDPIAware();
    SetCursorPos(x, y); System.Threading.Thread.Sleep(120);
    mouse_event(0x0002,0,0,0,IntPtr.Zero); System.Threading.Thread.Sleep(60);
    mouse_event(0x0004,0,0,0,IntPtr.Zero);
  }
}
'@
[M]::Click($X, $Y)
Write-Output "CLICK $X,$Y"
