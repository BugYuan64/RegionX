param([int]$ProcId, [string]$Out)
Add-Type -AssemblyName System.Drawing
$refs = @([System.Drawing.Bitmap].Assembly.Location, [System.Drawing.Graphics].Assembly.Location)
Add-Type -ReferencedAssemblies $refs -TypeDefinition @'
using System; using System.Text; using System.Runtime.InteropServices;
using System.Drawing; using System.Drawing.Imaging;
public static class Cap {
  delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc p, IntPtr l);
  [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] static extern bool SetWindowPos(IntPtr h, IntPtr a, int x,int y,int cx,int cy,uint f);
  [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] static extern bool PrintWindow(IntPtr h, IntPtr hdc, uint flags);
  struct RECT { public int L, T, R, B; }
  public static string Run(uint pid, string outPrefix) {
    SetProcessDPIAware();
    var sb = new StringBuilder();
    var handles = new System.Collections.Generic.List<IntPtr>();
    EnumProc collect = (h, l) => { uint p; GetWindowThreadProcessId(h, out p); if (p == pid && IsWindowVisible(h)) handles.Add(h); return true; };
    EnumWindows(collect, IntPtr.Zero); GC.KeepAlive(collect);
    foreach (var h in handles) {
      var t = new StringBuilder(256); GetWindowText(h, t, 256);
      if (t.Length == 0) continue;
      RECT r; GetWindowRect(h, out r);
      SetWindowPos(h, new IntPtr(-1), r.L, r.T, 0, 0, 0x0001 | 0x0040);
      System.Threading.Thread.Sleep(700);
      GetWindowRect(h, out r);
      int w = r.R - r.L, ht = r.B - r.T;
      if (w <= 0 || ht <= 0) continue;
      using (var bmp = new Bitmap(w, ht)) {
        using (var g = Graphics.FromImage(bmp)) { var hdc = g.GetHdc(); PrintWindow(h, hdc, 2); g.ReleaseHdc(hdc); }
        var safe = t.ToString().Replace("/", "_").Replace("\\", "_").Replace(":", "_");
        var path = outPrefix + "-" + safe + ".png";
        bmp.Save(path, ImageFormat.Png);
        sb.AppendLine("CAP " + w + "x" + ht + " " + path);
      }
    }
    return sb.ToString();
  }
}
'@
[Cap]::Run([uint32]$ProcId, $Out)
