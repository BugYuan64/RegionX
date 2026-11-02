param([int]$ProcId)
Add-Type @'
using System; using System.Text; using System.Runtime.InteropServices;
public static class VW {
  public delegate bool P(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern bool EnumWindows(P p, IntPtr l);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out R r);
  public struct R { public int L,T,Ri,B; }
  public static string List(uint pid) { SetProcessDPIAware(); var sb=new StringBuilder();
    P cb=(h,l)=>{ uint p; GetWindowThreadProcessId(h,out p);
      if(p==pid){ var t=new StringBuilder(256); GetWindowText(h,t,256);
        if(t.Length>0){ R r; GetWindowRect(h,out r);
          sb.AppendLine((IsWindowVisible(h)?"VIS ":"hid ") + r.L+","+r.T+" "+(r.Ri-r.L)+"x"+(r.B-r.T)+" ["+t+"]"); } }
      return true; };
    EnumWindows(cb,IntPtr.Zero); GC.KeepAlive(cb); return sb.ToString(); } }
'@
[VW]::List([uint32]$ProcId)
