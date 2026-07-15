param(
    [object] $ConcurrencyValues = @(64, 256, 512, 1000),
    [int] $DurationSeconds = 8,
    [int] $WarmupSeconds = 2,
    [int] $IdleSeconds = 5,
    [int] $AppPort = 18081,
    [int] $ProviderPort = 20880,
    [string] $RuntimeProfile = "micro-dubbo",
    [int] $JniWorkers = 1,
    [int] $JniQueueCapacity = 128,
    [int] $NativeConnectionsPerEndpoint = 2,
    [int] $NativeMaxIdleConnectionsPerEndpoint = 2,
    [int] $NativeAsyncWorkers = 2,
    [int] $NativeAsyncQueueCapacity = 64,
    [string] $NativeAsyncTransport = "blocking",
    [int] $DubboMaxInflight = 32,
    [switch] $RandomizeRunOrder,
    [switch] $SkipBuild,
    [switch] $KeepProcesses
)

$ErrorActionPreference = "Stop"
$ScriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $ScriptDirectory "dubbo_benchmark_common.ps1")

$context = New-DubboBenchmarkContext -ScriptDirectory $ScriptDirectory
$context = Initialize-NativeStaticBenchmarkArtifacts -Context $context -SkipBuild:$SkipBuild
$concurrencyList = Convert-ToIntList -Value $ConcurrencyValues -Name "ConcurrencyValues"
$resultsDirectory = Join-Path $ScriptDirectory ("results\run_{0}" -f (Get-Date -Format "yyyyMMdd_HHmmss"))
New-Item -ItemType Directory -Force -Path $resultsDirectory | Out-Null

$endpoints = @(
    [PSCustomObject]@{ Name = "health"; Path = "/app/health"; CallsProvider = $false },
    [PSCustomObject]@{ Name = "dubbo_catalog"; Path = "/api/v1/catalog/nested"; CallsProvider = $true }
)
$results = [Collections.Generic.List[object]]::new()
$memoryPhases = [Collections.Generic.List[object]]::new()
$provider = $null
$consumer = $null

try {
    $provider = Start-CatalogStaticProvider -Context $context -ResultsDirectory $resultsDirectory -Port $ProviderPort
    $consumer = Start-NativeStaticConsumer -Context $context -ResultsDirectory $resultsDirectory `
        -RunName "native_static" -Port $AppPort -Providers "127.0.0.1:$ProviderPort" `
        -RuntimeProfile $RuntimeProfile -JniWorkers $JniWorkers -JniQueueCapacity $JniQueueCapacity `
        -ConnectionsPerEndpoint $NativeConnectionsPerEndpoint `
        -MaxIdleConnectionsPerEndpoint $NativeMaxIdleConnectionsPerEndpoint `
        -NativeAsyncWorkers $NativeAsyncWorkers -NativeAsyncQueueCapacity $NativeAsyncQueueCapacity `
        -NativeAsyncTransport $NativeAsyncTransport -DubboMaxInflight $DubboMaxInflight

    Start-Sleep -Seconds 1
    $memoryPhases.Add([PSCustomObject]@{
        phase = "startup"
        memory = Get-ProcessMemorySnapshot -ProcessId $consumer.Id
    })

    foreach ($endpoint in $endpoints) {
        Invoke-DubboLoad -Context $context -ResultsDirectory $resultsDirectory `
            -Name "warmup_$($endpoint.Name)" -Url "http://127.0.0.1:$AppPort$($endpoint.Path)" `
            -Concurrency 16 -DurationSeconds $WarmupSeconds | Out-Null
    }
    $memoryPhases.Add([PSCustomObject]@{
        phase = "after_warmup"
        memory = Get-ProcessMemorySnapshot -ProcessId $consumer.Id
    })

    $workItems = foreach ($concurrency in $concurrencyList) {
        foreach ($endpoint in $endpoints) {
            [PSCustomObject]@{ Concurrency = $concurrency; Endpoint = $endpoint }
        }
    }
    if ($RandomizeRunOrder) {
        $workItems = @($workItems | Sort-Object { Get-Random })
    }

    foreach ($workItem in $workItems) {
        $concurrency = $workItem.Concurrency
        $endpoint = $workItem.Endpoint
        Reset-ConsumerMetrics -Port $AppPort
        $before = Get-ProcessMemorySnapshot -ProcessId $consumer.Id
        $load = Invoke-DubboLoad -Context $context -ResultsDirectory $resultsDirectory `
            -Name "$($endpoint.Name)_c$concurrency" `
            -Url "http://127.0.0.1:$AppPort$($endpoint.Path)" `
            -Concurrency $concurrency -DurationSeconds $DurationSeconds
        $after = Get-ProcessMemorySnapshot -ProcessId $consumer.Id
        Start-Sleep -Seconds $IdleSeconds
        $idle = Get-ProcessMemorySnapshot -ProcessId $consumer.Id
        $nativeDubbo = Invoke-JsonEndpoint `
            -Url "http://127.0.0.1:$AppPort/api/v1/catalog/dubbo-metrics"
        $nativeMemory = Invoke-JsonEndpoint -Url "http://127.0.0.1:$AppPort/app/native-diagnostics"
        $nativeDubbo | ConvertTo-Json -Depth 8 | Set-Content `
            -Path (Join-Path $resultsDirectory "$($endpoint.Name)_c$concurrency.dubbo-metrics.json") -Encoding UTF8
        $nativeMemory | ConvertTo-Json -Depth 8 | Set-Content `
            -Path (Join-Path $resultsDirectory "$($endpoint.Name)_c$concurrency.native-memory.json") -Encoding UTF8

        $results.Add([PSCustomObject]@{
            scenario = "native_static"
            endpoint = $endpoint.Name
            path = $endpoint.Path
            calls_provider = $endpoint.CallsProvider
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
            working_set_before_mb = $before.working_set_mb
            working_set_after_mb = $after.working_set_mb
            working_set_idle_mb = $idle.working_set_mb
            private_idle_mb = $idle.private_mb
            threads_after = $after.threads
            native_dubbo_calls = $nativeDubbo.nativeDubboCalls
            native_dubbo_rejected = $nativeDubbo.nativeDubboRejected
            native_dubbo_errors = $nativeDubbo.nativeDubboErrors
            native_dubbo_avg_us = $nativeDubbo.nativeDubboAvgLatencyUs
        })
    }
} finally {
    if (-not $KeepProcesses) {
        Stop-ProcessSafely $consumer
        Stop-ProcessSafely $provider
    }
}

$jsonPath = Join-Path $resultsDirectory "summary.json"
$memoryPath = Join-Path $resultsDirectory "memory_phases.json"
$results | ConvertTo-Json -Depth 8 | Set-Content -Path $jsonPath -Encoding UTF8
$memoryPhases | ConvertTo-Json -Depth 8 | Set-Content -Path $memoryPath -Encoding UTF8

$summary = [Text.StringBuilder]::new()
[void] $summary.AppendLine("# Native-Static Dubbo Consumer Benchmark")
[void] $summary.AppendLine()
[void] $summary.AppendLine("This benchmark uses the current sample consumer/provider projects. The health route is a non-RPC control route inside the same Dubbo-enabled process; it is not presented as a Dubbo-disabled baseline.")
[void] $summary.AppendLine()
[void] $summary.AppendLine("- Runtime profile: $RuntimeProfile")
[void] $summary.AppendLine("- JNI workers / queue: $JniWorkers / $JniQueueCapacity")
[void] $summary.AppendLine("- Native connections / max idle: $NativeConnectionsPerEndpoint / $NativeMaxIdleConnectionsPerEndpoint")
[void] $summary.AppendLine("- Native async transport: $NativeAsyncTransport")
[void] $summary.AppendLine("- Native async workers / queue: $NativeAsyncWorkers / $NativeAsyncQueueCapacity")
[void] $summary.AppendLine("- Dubbo max in-flight: $DubboMaxInflight")
[void] $summary.AppendLine("- Duration per run: ${DurationSeconds}s")
[void] $summary.AppendLine("- Results: $resultsDirectory")
[void] $summary.AppendLine()
[void] $summary.AppendLine("| endpoint | provider call | c | rps | avg ms | p95 ms | p99 ms | errors | WS after MB | WS idle MB | private idle MB |")
[void] $summary.AppendLine("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
foreach ($row in $results) {
    [void] $summary.AppendLine("| $($row.endpoint) | $($row.calls_provider) | $($row.concurrency) | $($row.rps) | $($row.avg_ms) | $($row.p95_ms) | $($row.p99_ms) | $($row.errors) | $($row.working_set_after_mb) | $($row.working_set_idle_mb) | $($row.private_idle_mb) |")
}

$summaryPath = Join-Path $resultsDirectory "summary.md"
$summary.ToString() | Set-Content -Path $summaryPath -Encoding UTF8
Write-Output "summary: $summaryPath"
Write-Output "json: $jsonPath"
Write-Output "memory: $memoryPath"
