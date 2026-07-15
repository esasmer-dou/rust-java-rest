param(
    [object] $PoolSizes = @(1, 2, 4, 8, 16),
    [object] $ConcurrencyValues = @(64, 256, 512, 1000),
    [int] $DurationSeconds = 6,
    [int] $WarmupSeconds = 2,
    [int] $IdleSeconds = 3,
    [int] $AppPort = 18082,
    [int] $ProviderPort = 20880,
    [string] $RuntimeProfile = "balanced-dubbo",
    [int] $JniWorkers = 16,
    [int] $JniQueueCapacity = 1024,
    [int] $NativeAsyncWorkers = 8,
    [int] $NativeAsyncQueueCapacity = 1024,
    [string] $NativeAsyncTransport = "tokio-demux",
    [int] $DubboMaxInflight = 512,
    [switch] $SkipBuild,
    [switch] $KeepProcesses
)

$ErrorActionPreference = "Stop"
$ScriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $ScriptDirectory "dubbo_benchmark_common.ps1")

$context = New-DubboBenchmarkContext -ScriptDirectory $ScriptDirectory
$context = Initialize-NativeStaticBenchmarkArtifacts -Context $context -SkipBuild:$SkipBuild
$poolList = Convert-ToIntList -Value $PoolSizes -Name "PoolSizes"
$concurrencyList = Convert-ToIntList -Value $ConcurrencyValues -Name "ConcurrencyValues"
$resultsDirectory = Join-Path $ScriptDirectory ("results\native_pool_{0}" -f (Get-Date -Format "yyyyMMdd_HHmmss"))
New-Item -ItemType Directory -Force -Path $resultsDirectory | Out-Null

$endpoint = "/api/v1/catalog/nested"
$results = [Collections.Generic.List[object]]::new()
$provider = $null
$consumer = $null

try {
    $provider = Start-CatalogStaticProvider -Context $context -ResultsDirectory $resultsDirectory -Port $ProviderPort

    foreach ($poolSize in $poolList) {
        $runName = "pool_$poolSize"
        $consumer = Start-NativeStaticConsumer -Context $context -ResultsDirectory $resultsDirectory `
            -RunName $runName -Port $AppPort -Providers "127.0.0.1:$ProviderPort" `
            -RuntimeProfile $RuntimeProfile -JniWorkers $JniWorkers `
            -JniQueueCapacity $JniQueueCapacity -ConnectionsPerEndpoint $poolSize `
            -MaxIdleConnectionsPerEndpoint $poolSize -NativeAsyncWorkers $NativeAsyncWorkers `
            -NativeAsyncQueueCapacity $NativeAsyncQueueCapacity `
            -NativeAsyncTransport $NativeAsyncTransport -DubboMaxInflight $DubboMaxInflight

        Start-Sleep -Seconds 1
        $startupMemory = Get-ProcessMemorySnapshot -ProcessId $consumer.Id
        Invoke-DubboLoad -Context $context -ResultsDirectory $resultsDirectory `
            -Name "${runName}_warmup" -Url "http://127.0.0.1:$AppPort$endpoint" `
            -Concurrency 16 -DurationSeconds $WarmupSeconds | Out-Null
        $warmupMemory = Get-ProcessMemorySnapshot -ProcessId $consumer.Id

        foreach ($concurrency in $concurrencyList) {
            Reset-ConsumerMetrics -Port $AppPort
            $beforeMemory = Get-ProcessMemorySnapshot -ProcessId $consumer.Id
            $load = Invoke-DubboLoad -Context $context -ResultsDirectory $resultsDirectory `
                -Name "${runName}_c$concurrency" -Url "http://127.0.0.1:$AppPort$endpoint" `
                -Concurrency $concurrency -DurationSeconds $DurationSeconds
            $afterMemory = Get-ProcessMemorySnapshot -ProcessId $consumer.Id
            Start-Sleep -Seconds $IdleSeconds
            $idleMemory = Get-ProcessMemorySnapshot -ProcessId $consumer.Id
            $nativeDubbo = Invoke-JsonEndpoint `
                -Url "http://127.0.0.1:$AppPort/api/v1/catalog/dubbo-metrics"
            $nativeDubbo | ConvertTo-Json -Depth 8 | Set-Content `
                -Path (Join-Path $resultsDirectory "${runName}_c$concurrency.dubbo-metrics.json") -Encoding UTF8

            $results.Add([PSCustomObject]@{
                pool_size = $poolSize
                concurrency = $concurrency
                requests = $load.requests
                errors = $load.errors_total
                statuses = $load.statuses
                rps = [Math]::Round($load.rps, 2)
                avg_ms = [Math]::Round($load.latency_us.avg / 1000.0, 3)
                p50_ms = [Math]::Round($load.latency_us.p50 / 1000.0, 3)
                p95_ms = [Math]::Round($load.latency_us.p95 / 1000.0, 3)
                p99_ms = [Math]::Round($load.latency_us.p99 / 1000.0, 3)
                max_ms = [Math]::Round($load.latency_us.max / 1000.0, 3)
                ws_startup_mb = $startupMemory.working_set_mb
                ws_warmup_mb = $warmupMemory.working_set_mb
                ws_before_mb = $beforeMemory.working_set_mb
                ws_after_mb = $afterMemory.working_set_mb
                ws_idle_mb = $idleMemory.working_set_mb
                private_idle_mb = $idleMemory.private_mb
                threads_after = $afterMemory.threads
                native_calls = $nativeDubbo.nativeDubboCalls
                native_open_connections = $nativeDubbo.nativeDubboOpenConnections
                native_idle_connections = $nativeDubbo.nativeDubboIdleConnections
                native_opened = $nativeDubbo.nativeDubboConnectionsOpened
                native_reused = $nativeDubbo.nativeDubboConnectionsReused
                native_closed = $nativeDubbo.nativeDubboConnectionsClosed
                native_pool_exhausted = $nativeDubbo.nativeDubboPoolExhausted
                native_errors = $nativeDubbo.nativeDubboErrors
                native_timeouts = $nativeDubbo.nativeDubboTimeouts
                native_rejected = $nativeDubbo.nativeDubboRejected
                native_avg_us = $nativeDubbo.nativeDubboAvgLatencyUs
            })
        }

        Stop-ProcessSafely $consumer
        $consumer = $null
        Start-Sleep -Seconds 1
    }
} finally {
    if (-not $KeepProcesses) {
        Stop-ProcessSafely $consumer
        Stop-ProcessSafely $provider
    }
}

$jsonPath = Join-Path $resultsDirectory "summary.json"
$results | ConvertTo-Json -Depth 8 | Set-Content -Path $jsonPath -Encoding UTF8

$summary = [Text.StringBuilder]::new()
[void] $summary.AppendLine("# Native Dubbo Connection Pool Benchmark")
[void] $summary.AppendLine()
[void] $summary.AppendLine("This benchmark uses the current native-static consumer and catalog-static provider. It does not use the removed framework sample JAR.")
[void] $summary.AppendLine()
[void] $summary.AppendLine("- Endpoint: $endpoint")
[void] $summary.AppendLine("- Runtime profile: $RuntimeProfile")
[void] $summary.AppendLine("- Native async transport: $NativeAsyncTransport")
[void] $summary.AppendLine("- Duration per run: ${DurationSeconds}s")
[void] $summary.AppendLine("- Warmup: ${WarmupSeconds}s")
[void] $summary.AppendLine("- Results: $resultsDirectory")
[void] $summary.AppendLine()
[void] $summary.AppendLine("| pool | c | rps | avg ms | p95 ms | p99 ms | errors | WS idle MB | open conn | idle conn | opened | reused | exhausted | native avg us |")
[void] $summary.AppendLine("|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
foreach ($row in $results) {
    [void] $summary.AppendLine("| $($row.pool_size) | $($row.concurrency) | $($row.rps) | $($row.avg_ms) | $($row.p95_ms) | $($row.p99_ms) | $($row.errors) | $($row.ws_idle_mb) | $($row.native_open_connections) | $($row.native_idle_connections) | $($row.native_opened) | $($row.native_reused) | $($row.native_pool_exhausted) | $($row.native_avg_us) |")
}

$summaryPath = Join-Path $resultsDirectory "summary.md"
$summary.ToString() | Set-Content -Path $summaryPath -Encoding UTF8
Write-Output "summary: $summaryPath"
Write-Output "json: $jsonPath"
