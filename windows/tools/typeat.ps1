param([int]$X, [int]$Y, [string]$Text)
# 点击指定坐标，然后把 Text 输入到获得焦点的控件里（可编辑下拉框用）。
Add-Type -AssemblyName System.Windows.Forms
Add-Type @'
using System; using System.Runtime.InteropServices;
public static class TY {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f,uint x,uint y,uint d,IntPtr e);
  public static void ClickAt(int x, int y) {
    SetProcessDPIAware();
    SetCursorPos(x, y);
    System.Threading.Thread.Sleep(150);
    mouse_event(0x0002,0,0,0,IntPtr.Zero);
    System.Threading.Thread.Sleep(60);
    mouse_event(0x0004,0,0,0,IntPtr.Zero);
  }
}
'@
[TY]::ClickAt($X, $Y)
Start-Sleep -Milliseconds 250
[System.Windows.Forms.SendKeys]::SendWait('^a')
Start-Sleep -Milliseconds 120
[System.Windows.Forms.SendKeys]::SendWait($Text)
Write-Output "TYPED '$Text' @$X,$Y"
