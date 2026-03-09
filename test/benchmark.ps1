# Windows PowerShell Benchmark Script
# Simple HTTP load test using PowerShell

param(
    [int]$Requests = 1000,
    [int]$Threads = 10,
    [string]$Url = "http://localhost:8080/health"
)

Write-Host "=== Rust-Spring Simple Benchmark ===" -ForegroundColor Green
Write-Host "URL: $Url"
Write-Host "Total Requests: $Requests"
Write-Host "Threads: $Threads"
Write-Host ""

$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()

# Paralel istekler
$jobs = 1..$Threads | ForEach-Object {
    Start-Job -ScriptBlock {
        param($count, $url)
        $results = @()
        for ($i = 0; $i -lt $count; $i++) {
            $sw = [System.Diagnostics.Stopwatch]::StartNew()
            try {
                $null = Invoke-WebRequest -Uri $url -Method Get -UseBasicParsing
                $sw.Stop()
                $results += $sw.ElapsedMilliseconds
            } catch {
                $results += -1
            }
        }
        return $results
    } -ArgumentList ($Requests / $Threads), $Url
}

# Sonuclari bekle
$allResults = $jobs | Wait-Job | Receive-Job
$jobs | Remove-Job

$stopwatch.Stop()

# Istatistikler
$successResults = $allResults | Where-Object { $_ -gt 0 }
$failedCount = ($allResults | Where-Object { $_ -eq -1 }).Count

Write-Host "=== Results ===" -ForegroundColor Yellow
Write-Host "Total Time: $($stopwatch.ElapsedMilliseconds) ms"
Write-Host "Successful Requests: $($successResults.Count)"
Write-Host "Failed Requests: $failedCount"

if ($successResults.Count -gt 0) {
    $avgLatency = ($successResults | Measure-Object -Average).Average
    $minLatency = ($successResults | Measure-Object -Minimum).Minimum
    $maxLatency = ($successResults | Measure-Object -Maximum).Maximum

    Write-Host ""
    Write-Host "Latency (ms):"
    Write-Host "  Avg: $([math]::Round($avgLatency, 2))"
    Write-Host "  Min: $minLatency"
    Write-Host "  Max: $maxLatency"

    $rps = [math]::Round($successResults.Count / ($stopwatch.ElapsedMilliseconds / 1000), 2)
    Write-Host ""
    Write-Host "Requests/sec: $rps"
}

Write-Host ""
Write-Host "=== Benchmark Complete ===" -ForegroundColor Green
