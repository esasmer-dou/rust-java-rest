param(
    [object] $ConcurrencyValues = @(64, 256, 512, 1000),
    [int] $RepeatCount = 3,
    [int] $DurationSeconds = 8,
    [int] $WarmupSeconds = 2,
    [int] $IdleSeconds = 5,
    [int] $AppPort = 18081,
    [int] $ProviderPort = 20880,
    [string] $RuntimeProfile = "balanced-dubbo",
    [int] $JniWorkers = 16,
    [int] $JniQueueCapacity = 1024,
    [int] $NativeConnectionsPerEndpoint = 16,
    [int] $NativeAsyncWorkers = 8,
    [int] $NativeAsyncQueueCapacity = 1024,
    [int] $DubboMaxInflight = 512,
    [bool] $DubboCatalogAdaptiveEnabled = $true,
    [int] $DubboCatalogMinInflight = 16,
    [int] $DubboCatalogInitialInflight = 64,
    [int] $DubboCatalogMaxInflight = 64,
    [int] $DubboCatalogResponseTimeoutMs = 1200,
    [int] $DubboCatalogTargetLatencyMs = 150,
    [int] $DubboCatalogHighLatencyMs = 500,
    [int] $DubboCatalogRpcWorkers = 1,
    [int] $DubboCatalogRpcQueueCapacity = 0,
    [string] $DubboProviders = "",
    [bool] $UseZookeeper = $false,
    [switch] $SkipBuild
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$SingleRunScript = Join-Path $ScriptDir "run_dubbo_overhead.ps1"
$ResultsDir = Join-Path $ScriptDir ("results\repeat_{0}" -f (Get-Date -Format "yyyyMMdd_HHmmss"))
New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null

if ($RepeatCount -lt 1) {
    throw "RepeatCount must be >= 1."
}

if ($ConcurrencyValues -is [array]) {
    $ConcurrencyList = @($ConcurrencyValues | ForEach-Object { [int] $_ })
} else {
    $ConcurrencyList = @(
        "$ConcurrencyValues" -split "[,\s]+" |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            ForEach-Object { [int] $_ }
    )
}
if ($ConcurrencyList.Count -eq 0) {
    throw "At least one concurrency level is required."
}
$ConcurrencyText = ($ConcurrencyList -join ",")

function Read-JsonRows {
    param([string] $Path)
    $value = Get-Content -Raw -Path $Path | ConvertFrom-Json
    if ($value -is [array]) {
        return @($value)
    }
    return @($value)
}

function Get-Median {
    param([object[]] $Values)
    $numbers = @($Values | Where-Object { $null -ne $_ } | ForEach-Object { [double] $_ } | Sort-Object)
    if ($numbers.Count -eq 0) {
        return 0
    }
    $mid = [int][Math]::Floor($numbers.Count / 2)
    if (($numbers.Count % 2) -eq 1) {
        return [Math]::Round($numbers[$mid], 3)
    }
    return [Math]::Round(($numbers[$mid - 1] + $numbers[$mid]) / 2.0, 3)
}

$combined = New-Object System.Collections.Generic.List[object]
$runManifests = New-Object System.Collections.Generic.List[object]

for ($repeat = 1; $repeat -le $RepeatCount; $repeat++) {
    $args = @(
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        $SingleRunScript,
        "-ConcurrencyValues",
        $ConcurrencyText,
        "-DurationSeconds",
        "$DurationSeconds",
        "-WarmupSeconds",
        "$WarmupSeconds",
        "-IdleSeconds",
        "$IdleSeconds",
        "-AppPort",
        "$AppPort",
        "-ProviderPort",
        "$ProviderPort",
        "-RuntimeProfile",
        $RuntimeProfile,
        "-JniWorkers",
        "$JniWorkers",
        "-JniQueueCapacity",
        "$JniQueueCapacity",
        "-NativeConnectionsPerEndpoint",
        "$NativeConnectionsPerEndpoint",
        "-NativeAsyncWorkers",
        "$NativeAsyncWorkers",
        "-NativeAsyncQueueCapacity",
        "$NativeAsyncQueueCapacity",
        "-DubboMaxInflight",
        "$DubboMaxInflight",
        "-DubboCatalogAdaptiveEnabled",
        "$DubboCatalogAdaptiveEnabled",
        "-DubboCatalogMinInflight",
        "$DubboCatalogMinInflight",
        "-DubboCatalogInitialInflight",
        "$DubboCatalogInitialInflight",
        "-DubboCatalogMaxInflight",
        "$DubboCatalogMaxInflight",
        "-DubboCatalogResponseTimeoutMs",
        "$DubboCatalogResponseTimeoutMs",
        "-DubboCatalogTargetLatencyMs",
        "$DubboCatalogTargetLatencyMs",
        "-DubboCatalogHighLatencyMs",
        "$DubboCatalogHighLatencyMs",
        "-DubboCatalogRpcWorkers",
        "$DubboCatalogRpcWorkers",
        "-DubboCatalogRpcQueueCapacity",
        "$DubboCatalogRpcQueueCapacity",
        "-DubboProviders",
        $DubboProviders,
        "-UseZookeeper",
        "$UseZookeeper",
        "-RandomizeRunOrder"
    )
    if ($SkipBuild -or $repeat -gt 1) {
        $args += "-SkipBuild"
    }

    $outPath = Join-Path $ResultsDir "repeat_${repeat}.out.log"
    $errPath = Join-Path $ResultsDir "repeat_${repeat}.err.log"
    $process = Start-Process -FilePath "powershell" -ArgumentList $args `
        -RedirectStandardOutput $outPath -RedirectStandardError $errPath `
        -WindowStyle Hidden -PassThru -Wait
    if ($process.ExitCode -ne 0) {
        throw "Repeat $repeat failed. See $outPath and $errPath"
    }

    $output = Get-Content -Path $outPath -ErrorAction SilentlyContinue
    $jsonLine = $output | Where-Object { "$_" -like "json:*" } | Select-Object -Last 1
    $summaryLine = $output | Where-Object { "$_" -like "summary:*" } | Select-Object -Last 1
    if ($null -eq $jsonLine) {
        throw "Repeat $repeat did not report a json path."
    }
    $jsonPath = "$jsonLine".Substring(5).Trim()
    $summaryPath = if ($null -ne $summaryLine) { "$summaryLine".Substring(8).Trim() } else { "" }

    foreach ($row in (Read-JsonRows $jsonPath)) {
        $row | Add-Member -NotePropertyName repeat -NotePropertyValue $repeat -Force
        $row | Add-Member -NotePropertyName runtime_profile -NotePropertyValue $RuntimeProfile -Force
        $row | Add-Member -NotePropertyName jni_workers -NotePropertyValue $JniWorkers -Force
        $row | Add-Member -NotePropertyName native_connections_per_endpoint -NotePropertyValue $NativeConnectionsPerEndpoint -Force
        $row | Add-Member -NotePropertyName native_async_workers -NotePropertyValue $NativeAsyncWorkers -Force
        $row | Add-Member -NotePropertyName native_async_queue_capacity -NotePropertyValue $NativeAsyncQueueCapacity -Force
        $combined.Add($row)
    }

    $runManifests.Add([PSCustomObject]@{
        repeat = $repeat
        json = $jsonPath
        summary = $summaryPath
    })
}

$medianRows = New-Object System.Collections.Generic.List[object]
$groups = $combined | Group-Object { "$($_.scenario)|$($_.endpoint)|$($_.concurrency)" }
foreach ($group in $groups) {
    $first = $group.Group | Select-Object -First 1
    $medianRows.Add([PSCustomObject]@{
        scenario = $first.scenario
        endpoint = $first.endpoint
        path = $first.path
        concurrency = [int] $first.concurrency
        runs = $group.Count
        rps_median = Get-Median -Values @($group.Group | ForEach-Object { $_.rps })
        avg_ms_median = Get-Median -Values @($group.Group | ForEach-Object { $_.avg_ms })
        p95_ms_median = Get-Median -Values @($group.Group | ForEach-Object { $_.p95_ms })
        p99_ms_median = Get-Median -Values @($group.Group | ForEach-Object { $_.p99_ms })
        errors_median = Get-Median -Values @($group.Group | ForEach-Object { $_.errors })
        status_200_median = Get-Median -Values @($group.Group | ForEach-Object { if ($_.statuses.PSObject.Properties["200"]) { $_.statuses."200" } else { 0 } })
        status_503_median = Get-Median -Values @($group.Group | ForEach-Object { if ($_.statuses.PSObject.Properties["503"]) { $_.statuses."503" } else { 0 } })
        ws_idle_mb_median = Get-Median -Values @($group.Group | ForEach-Object { $_.working_set_idle_mb })
        private_idle_mb_median = Get-Median -Values @($group.Group | ForEach-Object { $_.private_idle_mb })
    })
}
$medianRows = @($medianRows | Sort-Object scenario, endpoint, concurrency)

$combinedPath = Join-Path $ResultsDir "combined_runs.json"
$medianPath = Join-Path $ResultsDir "median_summary.json"
$manifestPath = Join-Path $ResultsDir "runs.json"
$combined | ConvertTo-Json -Depth 8 | Set-Content -Path $combinedPath -Encoding UTF8
$medianRows | ConvertTo-Json -Depth 8 | Set-Content -Path $medianPath -Encoding UTF8
$runManifests | ConvertTo-Json -Depth 4 | Set-Content -Path $manifestPath -Encoding UTF8

$summary = New-Object System.Text.StringBuilder
[void]$summary.AppendLine("# Dubbo Consumer Repeat Benchmark")
[void]$summary.AppendLine()
[void]$summary.AppendLine("- Repeats: $RepeatCount")
[void]$summary.AppendLine("- Duration per run: ${DurationSeconds}s")
[void]$summary.AppendLine("- Warmup per endpoint: ${WarmupSeconds}s")
[void]$summary.AppendLine("- Runtime profile: $RuntimeProfile")
[void]$summary.AppendLine("- JNI workers: $JniWorkers")
[void]$summary.AppendLine("- JNI queue capacity: $JniQueueCapacity")
[void]$summary.AppendLine("- Dubbo providers override: $DubboProviders")
[void]$summary.AppendLine("- Use Zookeeper: $UseZookeeper")
[void]$summary.AppendLine("- Native Dubbo connections per endpoint: $NativeConnectionsPerEndpoint")
[void]$summary.AppendLine("- Native Dubbo async workers: $NativeAsyncWorkers")
[void]$summary.AppendLine("- Native Dubbo async queue capacity: $NativeAsyncQueueCapacity")
[void]$summary.AppendLine("- Dubbo max inflight: $DubboMaxInflight")
[void]$summary.AppendLine("- Dubbo catalog adaptive: $DubboCatalogAdaptiveEnabled")
[void]$summary.AppendLine("- Dubbo catalog min inflight: $DubboCatalogMinInflight")
[void]$summary.AppendLine("- Dubbo catalog initial inflight: $DubboCatalogInitialInflight")
[void]$summary.AppendLine("- Dubbo catalog bulkhead: $DubboCatalogMaxInflight")
[void]$summary.AppendLine("- Dubbo catalog response timeout ms: $DubboCatalogResponseTimeoutMs")
[void]$summary.AppendLine("- Dubbo catalog target latency ms: $DubboCatalogTargetLatencyMs")
[void]$summary.AppendLine("- Dubbo catalog high latency ms: $DubboCatalogHighLatencyMs")
[void]$summary.AppendLine("- Dubbo catalog RPC workers: $DubboCatalogRpcWorkers")
[void]$summary.AppendLine("- Dubbo catalog RPC queue capacity: $DubboCatalogRpcQueueCapacity")
[void]$summary.AppendLine("- Order: randomized per repeat")
[void]$summary.AppendLine("- Results: $ResultsDir")
[void]$summary.AppendLine()
[void]$summary.AppendLine("| scenario | endpoint | c | runs | median rps | median p95 ms | median p99 ms | median errors | median 200 | median 503 | median WS idle MB | median private idle MB |")
[void]$summary.AppendLine("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
foreach ($row in $medianRows) {
    [void]$summary.AppendLine("| $($row.scenario) | $($row.endpoint) | $($row.concurrency) | $($row.runs) | $($row.rps_median) | $($row.p95_ms_median) | $($row.p99_ms_median) | $($row.errors_median) | $($row.status_200_median) | $($row.status_503_median) | $($row.ws_idle_mb_median) | $($row.private_idle_mb_median) |")
}

[void]$summary.AppendLine()
[void]$summary.AppendLine("## Median Baseline vs Dubbo Enabled on Non-Dubbo Endpoints")
[void]$summary.AppendLine()
[void]$summary.AppendLine("| endpoint | c | baseline rps | dubbo-enabled rps | rps delta % | baseline p99 ms | dubbo-enabled p99 ms | p99 delta % | idle WS delta MB |")
[void]$summary.AppendLine("|---|---:|---:|---:|---:|---:|---:|---:|---:|")
foreach ($endpointName in @("raw", "candidates")) {
    foreach ($concurrency in $ConcurrencyList) {
        $base = $medianRows | Where-Object { $_.scenario -eq "baseline_no_dubbo" -and $_.endpoint -eq $endpointName -and $_.concurrency -eq $concurrency } | Select-Object -First 1
        $dubbo = $medianRows | Where-Object { $_.scenario -eq "dubbo_enabled" -and $_.endpoint -eq $endpointName -and $_.concurrency -eq $concurrency } | Select-Object -First 1
        if ($base -and $dubbo) {
            $rpsDelta = if ($base.rps_median -gt 0) { [Math]::Round((($dubbo.rps_median - $base.rps_median) / $base.rps_median) * 100, 2) } else { 0 }
            $p99Delta = if ($base.p99_ms_median -gt 0) { [Math]::Round((($dubbo.p99_ms_median - $base.p99_ms_median) / $base.p99_ms_median) * 100, 2) } else { 0 }
            $wsDelta = [Math]::Round($dubbo.ws_idle_mb_median - $base.ws_idle_mb_median, 2)
            [void]$summary.AppendLine("| $endpointName | $concurrency | $($base.rps_median) | $($dubbo.rps_median) | $rpsDelta | $($base.p99_ms_median) | $($dubbo.p99_ms_median) | $p99Delta | $wsDelta |")
        }
    }
}

$summaryPath = Join-Path $ResultsDir "summary.md"
$summary.ToString() | Set-Content -Path $summaryPath -Encoding UTF8

Write-Output "summary: $summaryPath"
Write-Output "median_json: $medianPath"
Write-Output "combined_json: $combinedPath"
