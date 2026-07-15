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
    [int] $NativeMaxIdleConnectionsPerEndpoint = 4,
    [int] $NativeAsyncWorkers = 8,
    [int] $NativeAsyncQueueCapacity = 1024,
    [string] $NativeAsyncTransport = "tokio-demux",
    [int] $DubboMaxInflight = 512,
    [switch] $SkipBuild
)

$ErrorActionPreference = "Stop"
$ScriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $ScriptDirectory "dubbo_benchmark_common.ps1")

if ($RepeatCount -lt 1) {
    throw "RepeatCount must be at least 1."
}
$concurrencyList = Convert-ToIntList -Value $ConcurrencyValues -Name "ConcurrencyValues"
$concurrencyText = $concurrencyList -join ","
$singleRunScript = Join-Path $ScriptDirectory "run_dubbo_overhead.ps1"
$resultsDirectory = Join-Path $ScriptDirectory ("results\repeat_{0}" -f (Get-Date -Format "yyyyMMdd_HHmmss"))
New-Item -ItemType Directory -Force -Path $resultsDirectory | Out-Null

function Read-JsonRows {
    param([Parameter(Mandatory = $true)][string] $Path)

    $value = Get-Content -Raw -Path $Path | ConvertFrom-Json
    return @($value)
}

function Get-Median {
    param([Parameter(Mandatory = $true)][object[]] $Values)

    $numbers = @($Values | Where-Object { $null -ne $_ } | ForEach-Object { [double] $_ } | Sort-Object)
    if ($numbers.Count -eq 0) {
        return 0
    }
    $middle = [int] [Math]::Floor($numbers.Count / 2)
    if (($numbers.Count % 2) -eq 1) {
        return [Math]::Round($numbers[$middle], 3)
    }
    return [Math]::Round(($numbers[$middle - 1] + $numbers[$middle]) / 2.0, 3)
}

$combined = [Collections.Generic.List[object]]::new()
$runManifests = [Collections.Generic.List[object]]::new()

for ($repeat = 1; $repeat -le $RepeatCount; $repeat++) {
    $arguments = @(
        "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $singleRunScript,
        "-ConcurrencyValues", $concurrencyText,
        "-DurationSeconds", "$DurationSeconds",
        "-WarmupSeconds", "$WarmupSeconds",
        "-IdleSeconds", "$IdleSeconds",
        "-AppPort", "$AppPort",
        "-ProviderPort", "$ProviderPort",
        "-RuntimeProfile", $RuntimeProfile,
        "-JniWorkers", "$JniWorkers",
        "-JniQueueCapacity", "$JniQueueCapacity",
        "-NativeConnectionsPerEndpoint", "$NativeConnectionsPerEndpoint",
        "-NativeMaxIdleConnectionsPerEndpoint", "$NativeMaxIdleConnectionsPerEndpoint",
        "-NativeAsyncWorkers", "$NativeAsyncWorkers",
        "-NativeAsyncQueueCapacity", "$NativeAsyncQueueCapacity",
        "-NativeAsyncTransport", $NativeAsyncTransport,
        "-DubboMaxInflight", "$DubboMaxInflight",
        "-RandomizeRunOrder"
    )
    if ($SkipBuild -or $repeat -gt 1) {
        $arguments += "-SkipBuild"
    }

    $stdout = Join-Path $resultsDirectory "repeat_${repeat}.out.log"
    $stderr = Join-Path $resultsDirectory "repeat_${repeat}.err.log"
    $process = Start-Process -FilePath "powershell" -ArgumentList $arguments `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr `
        -WindowStyle Hidden -PassThru -Wait
    if ($process.ExitCode -ne 0) {
        throw "Repeat $repeat failed. See $stdout and $stderr."
    }

    $output = Get-Content -Path $stdout
    $jsonLine = $output | Where-Object { "$_" -like "json:*" } | Select-Object -Last 1
    $summaryLine = $output | Where-Object { "$_" -like "summary:*" } | Select-Object -Last 1
    if ($null -eq $jsonLine) {
        throw "Repeat $repeat did not report a JSON result path."
    }
    $jsonPath = "$jsonLine".Substring(5).Trim()
    $summaryPath = if ($null -ne $summaryLine) { "$summaryLine".Substring(8).Trim() } else { "" }

    foreach ($row in (Read-JsonRows -Path $jsonPath)) {
        $row | Add-Member -NotePropertyName repeat -NotePropertyValue $repeat -Force
        $combined.Add($row)
    }
    $runManifests.Add([PSCustomObject]@{ repeat = $repeat; json = $jsonPath; summary = $summaryPath })
}

$medianRows = [Collections.Generic.List[object]]::new()
foreach ($group in ($combined | Group-Object { "$($_.endpoint)|$($_.concurrency)" })) {
    $first = $group.Group | Select-Object -First 1
    $medianRows.Add([PSCustomObject]@{
        endpoint = $first.endpoint
        path = $first.path
        calls_provider = $first.calls_provider
        concurrency = [int] $first.concurrency
        runs = $group.Count
        rps_median = Get-Median -Values @($group.Group | ForEach-Object { $_.rps })
        avg_ms_median = Get-Median -Values @($group.Group | ForEach-Object { $_.avg_ms })
        p95_ms_median = Get-Median -Values @($group.Group | ForEach-Object { $_.p95_ms })
        p99_ms_median = Get-Median -Values @($group.Group | ForEach-Object { $_.p99_ms })
        errors_median = Get-Median -Values @($group.Group | ForEach-Object { $_.errors })
        status_200_median = Get-Median -Values @($group.Group | ForEach-Object {
            if ($_.statuses.PSObject.Properties["200"]) { $_.statuses."200" } else { 0 }
        })
        status_503_median = Get-Median -Values @($group.Group | ForEach-Object {
            if ($_.statuses.PSObject.Properties["503"]) { $_.statuses."503" } else { 0 }
        })
        ws_idle_mb_median = Get-Median -Values @($group.Group | ForEach-Object { $_.working_set_idle_mb })
        private_idle_mb_median = Get-Median -Values @($group.Group | ForEach-Object { $_.private_idle_mb })
    })
}
$medianRows = @($medianRows | Sort-Object endpoint, concurrency)

$combinedPath = Join-Path $resultsDirectory "combined_runs.json"
$medianPath = Join-Path $resultsDirectory "median_summary.json"
$manifestPath = Join-Path $resultsDirectory "runs.json"
$combined | ConvertTo-Json -Depth 8 | Set-Content -Path $combinedPath -Encoding UTF8
$medianRows | ConvertTo-Json -Depth 8 | Set-Content -Path $medianPath -Encoding UTF8
$runManifests | ConvertTo-Json -Depth 4 | Set-Content -Path $manifestPath -Encoding UTF8

$summary = [Text.StringBuilder]::new()
[void] $summary.AppendLine("# Native-Static Dubbo Consumer Repeat Benchmark")
[void] $summary.AppendLine()
[void] $summary.AppendLine("The health route is a control route in the same Dubbo-enabled process, not a Dubbo-disabled baseline.")
[void] $summary.AppendLine()
[void] $summary.AppendLine("- Repeats: $RepeatCount")
[void] $summary.AppendLine("- Runtime profile: $RuntimeProfile")
[void] $summary.AppendLine("- Native async transport: $NativeAsyncTransport")
[void] $summary.AppendLine("- Results: $resultsDirectory")
[void] $summary.AppendLine()
[void] $summary.AppendLine("| endpoint | provider call | c | runs | median rps | median p95 ms | median p99 ms | median errors | median 200 | median 503 | median WS idle MB | median private idle MB |")
[void] $summary.AppendLine("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
foreach ($row in $medianRows) {
    [void] $summary.AppendLine("| $($row.endpoint) | $($row.calls_provider) | $($row.concurrency) | $($row.runs) | $($row.rps_median) | $($row.p95_ms_median) | $($row.p99_ms_median) | $($row.errors_median) | $($row.status_200_median) | $($row.status_503_median) | $($row.ws_idle_mb_median) | $($row.private_idle_mb_median) |")
}

$summaryPath = Join-Path $resultsDirectory "summary.md"
$summary.ToString() | Set-Content -Path $summaryPath -Encoding UTF8
Write-Output "summary: $summaryPath"
Write-Output "median_json: $medianPath"
Write-Output "combined_json: $combinedPath"
