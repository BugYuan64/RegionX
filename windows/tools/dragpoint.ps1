param([int]$X1, [int]$Y1, [int]$X2, [int]$Y2)
Add-Type @'
using System; using System.Runtime.InteropServices;
public static class D {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f,uint x,uint y,uint d,IntPtr e);
  public static void Drag(int x1,int y1,int x2,int y2) {
    SetProcessDPIAware();
    SetCursorPos(x1,y1); System.Threading.Thread.Sleep(200);
    mouse_event(0x0002,0,0,0,IntPtr.Zero); System.Threading.Thread.Sleep(150);
    int steps = 12;
    for (int i=1;i<=steps;i++) {
      SetCursorPos(x1 + (x2-x1)*i/steps, y1 + (y2-y1)*i/steps);
      System.Threading.Thread.Sleep(40);
    }
    System.Threading.Thread.Sleep(150);
    mouse_event(0x0004,0,0,0,IntPtr.Zero);
  }
}
'@
[D]::Drag($X1, $Y1, $X2, $Y2)
Write-Output "DRAG $X1,$Y1 -> $X2,$Y2"
