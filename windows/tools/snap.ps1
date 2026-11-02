Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$refs = @([System.Drawing.Bitmap].Assembly.Location)
Add-Type -ReferencedAssemblies $refs -TypeDefinition @'
using System; using System.Runtime.InteropServices; using System.Drawing; using System.Drawing.Imaging;
public static class Dpi {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
}
'@
[Dpi]::SetProcessDPIAware() | Out-Null
$path = $args[0]
$rect = [System.Windows.Forms.SystemInformation]::VirtualScreen
$bmp = New-Object System.Drawing.Bitmap -ArgumentList @($rect.Width, $rect.Height)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($rect.X, $rect.Y, 0, 0, $bmp.Size)
$bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()
Write-Output "SNAP $($rect.Width)x$($rect.Height)"
