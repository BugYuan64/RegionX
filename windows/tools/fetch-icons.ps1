param([string]$Collection, [string]$Icons, [string]$Out)
# 从 iconify 取图标，校验 not_found 为空才保存（用 Invoke-WebRequest，走系统代理）。
$url = "https://api.iconify.design/$Collection.json?icons=$Icons"
for ($i = 1; $i -le 5; $i++) {
    try {
        $resp = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 30
        $text = [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
        $json = $text | ConvertFrom-Json
        $missing = @($json.not_found)
        $found = @($json.icons.PSObject.Properties.Name)
        if ($missing.Count -gt 0) { Write-Output "MISSING $($missing -join ',')"; exit 0 }
        if ($found.Count -eq 0) { Start-Sleep -Milliseconds 900; continue }
        [System.IO.File]::WriteAllText($Out, $text, [System.Text.Encoding]::UTF8)
        Write-Output "OK $Out found=$($found -join ',')"
        exit 0
    } catch {
        Start-Sleep -Milliseconds 900
    }
}
Write-Output "FAIL $url"
exit 1
