param([string]$Out, [string]$Only = '')
# 把 BrandIcons.xaml 里的每个品牌 Geometry 渲染成 PNG，确认不是空图形。
Add-Type -AssemblyName PresentationCore
Add-Type -AssemblyName PresentationFramework
Add-Type -AssemblyName WindowsBase
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$dictPath = (Resolve-Path (Join-Path $PSScriptRoot '..\src\ScreenAssistant.App\Styles\BrandIcons.xaml')).Path
$xml = [xml](Get-Content -Raw -Encoding UTF8 $dictPath)
$ns = New-Object System.Xml.XmlNamespaceManager($xml.NameTable)
$ns.AddNamespace('p', 'http://schemas.microsoft.com/winfx/2006/xaml/presentation')
$ns.AddNamespace('x', 'http://schemas.microsoft.com/winfx/2006/xaml')

$entries = @()
foreach ($node in $xml.SelectNodes('//p:Geometry', $ns)) {
    $key = $node.GetAttribute('Key', 'http://schemas.microsoft.com/winfx/2006/xaml')
    if ($Only -and $key -notin ($Only -split ',')) { continue }
    $entries += [pscustomobject]@{ Key = $key; Data = $node.InnerText.Trim() }
}

$cell = 110
$cols = [math]::Min(4, $entries.Count)
if ($cols -eq 0) { throw '没有匹配的图标资源' }
$rows = [math]::Ceiling($entries.Count / $cols)
$w = $cell * $cols
$h = $cell * $rows

$visual = New-Object System.Windows.Media.DrawingVisual
$dc = $visual.RenderOpen()
$dc.DrawRectangle([System.Windows.Media.Brushes]::White, $null, (New-Object System.Windows.Rect 0, 0, $w, $h))

$typeface = New-Object System.Windows.Media.Typeface('Segoe UI')

for ($i = 0; $i -lt $entries.Count; $i++) {
    $entry = $entries[$i]
    $geo = [System.Windows.Media.Geometry]::Parse($entry.Data)
    $b = $geo.Bounds
    if ($b.IsEmpty -or $b.Width -le 0 -or $b.Height -le 0) { Write-Output "$($entry.Key) EMPTY"; continue }

    $cx = [double](($i % $cols) * $cell)
    $cy = [double]([math]::Floor($i / $cols) * $cell)
    $pad = 16
    $size = $cell - 2 * $pad - 18
    $scale = [math]::Min($size / $b.Width, $size / $b.Height)
    $ox = $cx + $pad + ($size - $b.Width * $scale) / 2
    $oy = $cy + $pad + ($size - $b.Height * $scale) / 2

    $m = [System.Windows.Media.Matrix]::Identity
    $m.Scale($scale, $scale)
    $m.Translate($ox - $b.X * $scale, $oy - $b.Y * $scale)
    $g = $geo.Clone()
    $g.Transform = New-Object System.Windows.Media.MatrixTransform($m)
    $dc.DrawGeometry([System.Windows.Media.Brushes]::Black, $null, $g)

    $lx = [double]$cx + 8
    $ly = [double]$cy + [double]$cell - 22
    $label = New-Object System.Windows.Media.FormattedText(
        $entry.Key, [System.Globalization.CultureInfo]::InvariantCulture,
        [System.Windows.FlowDirection]::LeftToRight, $typeface, 11.0,
        [System.Windows.Media.Brushes]::Gray, 1.0)
    $dc.DrawText($label, (New-Object System.Windows.Point($lx, $ly)))

    Write-Output ("{0} bounds {1}x{2}" -f $entry.Key, [int]$b.Width, [int]$b.Height)
}
$dc.Close()

$rtb = New-Object System.Windows.Media.Imaging.RenderTargetBitmap($w, $h, 96, 96, [System.Windows.Media.PixelFormats]::Pbgra32)
$rtb.Render($visual)
$enc = New-Object System.Windows.Media.Imaging.PngBitmapEncoder
$enc.Frames.Add([System.Windows.Media.Imaging.BitmapFrame]::Create([System.Windows.Media.Imaging.BitmapSource]$rtb))
$fs = [System.IO.File]::Open($Out, [System.IO.FileMode]::Create)
$enc.Save($fs)
$fs.Close()
Write-Output "SAVED $Out"
