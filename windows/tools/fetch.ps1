param([string]$Url, [string]$Out, [int]$Retries = 8)
# 带重试的下载（这台机器到 jsdelivr 经常返回 000），用 .NET HttpClient。
Add-Type -AssemblyName System.Net.Http
$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(25)
for ($i = 1; $i -le $Retries; $i++) {
    try {
        $resp = $client.GetAsync($Url).GetAwaiter().GetResult()
        if ($resp.IsSuccessStatusCode) {
            $bytes = $resp.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
            [System.IO.File]::WriteAllBytes($Out, $bytes)
            Write-Output "OK $Out ($i tries, $($bytes.Length) bytes)"
            $client.Dispose()
            exit 0
        }
        Write-Output "status $($resp.StatusCode) try $i"
    } catch {
        Write-Output "error try $i : $($_.Exception.Message)"
    }
    Start-Sleep -Milliseconds 900
}
$client.Dispose()
Write-Output "FAIL $Url"
exit 1
