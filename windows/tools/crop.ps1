param([string]$In, [string]$Out, [int]$X, [int]$Y, [int]$W, [int]$H, [double]$Scale = 1)
Add-Type -AssemblyName System.Drawing
$src = [System.Drawing.Image]::FromFile($In)
$dw = [int]($W * $Scale)
$dh = [int]($H * $Scale)
$bmp = New-Object System.Drawing.Bitmap -ArgumentList @($dw, $dh)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
$dest = New-Object System.Drawing.Rectangle -ArgumentList @(0, 0, $dw, $dh)
$srect = New-Object System.Drawing.Rectangle -ArgumentList @($X, $Y, $W, $H)
$g.DrawImage($src, $dest, $srect, [System.Drawing.GraphicsUnit]::Pixel)
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose(); $src.Dispose()
Write-Output "CROP $Out ${W}x${H} scale=$Scale"
