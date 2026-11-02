param([int]$X, [int]$Y, [int]$W, [int]$H, [string]$Out)
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$refs = @([System.Drawing.Bitmap].Assembly.Location)
Add-Type -ReferencedAssemblies $refs -TypeDefinition @'
using System; using System.Runtime.InteropServices;
public static class Dpi2 { [DllImport("user32.dll")] public static extern bool SetProcessDPIAware(); }
'@
[Dpi2]::SetProcessDPIAware() | Out-Null
$bmp = New-Object System.Drawing.Bitmap -ArgumentList @($W, $H)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($X, $Y, 0, 0, $bmp.Size)
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()
Write-Output "SHOT $Out"
