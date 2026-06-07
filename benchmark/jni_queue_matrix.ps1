param(
    [ValidateSet("micro-rest", "micro-rest-plus", "micro-rss", "ultra-low-rss", "throughput")]
    [string] $RuntimeProfile = "micro-rest",
    [ValidateSet("sample", "minimal")]
    [string] $AppMode = "minimal",
    [string[]] $Cases = @(
        "baseline|1|128|512",
        "q256|1|256|512",
        "q512|1|512|512",
        "w2q256|2|256|512",
        "w2q512|2|512|512",
        "w2q512c768|2|512|768"
    ),
    [string[]] $ConcurrencyValues = @("256", "512"),
    [string[]] $EndpointSpecs = @("small-direct|/api/v1/candidates/direct"),
    [int] $RepeatCount = 2,
    [int] $DurationSeconds = 4,
    [int] $IdleSeconds = 2,
    [int] $FinalIdleSeconds = 10,
    [int] $HostPort = 18186,
    [string] $ResultsDir = "",
    [switch] $SkipBuild
)

$ErrorActionPreference = "Stop"
if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$LinuxSmapsScript = Join-Path $ScriptDir "linux_smaps_breakdown.ps1"

if ([string]::IsNullOrWhiteSpace($ResultsDir)) {
    $ResultsDir = Join-Path $ScriptDir ("results\jni_queue_matrix_{0}_{1}_{2}" -f $AppMode, $RuntimeProfile, (Get-Date -Format "yyyyMMdd_HHmmss"))
}
$ResultsDir = [System.IO.Path]::GetFullPath($ResultsDir)
New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null

function Convert-ToDouble {
    param($Value)
    if ($null -eq $Value -or [string]::IsNullOrWhiteSpace("$Value")) {
        return 0.0
    }
    return [double]::Parse(("$Value").Trim().Replace(",", "."), [System.Globalization.CultureInfo]::InvariantCulture)
}

function Convert-ToInt64 {
    param($Value)
    if ($null -eq $Value -or [string]::IsNullOrWhiteSpace("$Value")) {
        return 0L
    }
    return [int64] "$Value"
}

function Get-StatusCount {
    param([string] $StatusesJson, [string] $Code)
    if ([string]::IsNullOrWhiteSpace($StatusesJson)) {
        return 0L
    }
    try {
        $statuses = $StatusesJson | ConvertFrom-Json
        $property = $statuses.PSObject.Properties[$Code]
        if ($null -eq $property -or $null -eq $property.Value) {
            return 0L
        }
        return [int64] $property.Value
    } catch {
        return 0L
    }
}

function Get-PrometheusMetric {
    param([string] $MetricsPath, [string] $MetricName)
    if (-not (Test-Path $MetricsPath)) {
        return 0L
    }
    $pattern = "(?m)^$([regex]::Escape($MetricName))(?:\{[^}]*\})?\s+([0-9.]+)\s*$"
    $text = Get-Content -Path $MetricsPath -Raw
    $match = [regex]::Match($text, $pattern)
    if (-not $match.Success) {
        return 0L
    }
    return [int64] ([double]::Parse($match.Groups[1].Value, [System.Globalization.CultureInfo]::InvariantCulture))
}

function Parse-Case {
    param([string] $CaseSpec)
    $parts = $CaseSpec -split "\|"
    if ($parts.Count -ne 4) {
        throw "Case must be name|workers|queue|maxConnections: $CaseSpec"
    }
    [PSCustomObject]@{
        name = $parts[0].Trim()
        workers = [int] $parts[1].Trim()
        queue = [int] $parts[2].Trim()
        maxConnections = [int] $parts[3].Trim()
    }
}

$normalizedCases = @(
    $Cases |
        ForEach-Object { "$_" -split "," } |
        ForEach-Object { $_.Trim() } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        ForEach-Object { Parse-Case $_ }
)
if ($normalizedCases.Count -eq 0) {
    throw "At least one case is required."
}

$rows = New-Object 'System.Collections.Generic.List[object]'
$buildDone = [bool] $SkipBuild
for ($repeat = 1; $repeat -le $RepeatCount; $repeat++) {
    foreach ($case in $normalizedCases) {
        $safeCase = $case.name -replace '[^a-zA-Z0-9_-]+', '_'
        $runDir = Join-Path $ResultsDir ("{0}_r{1}" -f $safeCase, $repeat)
        $extraOpts = "-Dreactor.rust.native-trim.enabled=false " +
                "-Dreactor.rust.jni.workers=$($case.workers) " +
                "-Dreactor.rust.jni.queue-capacity=$($case.queue) " +
                "-Dreactor.rust.http.max-connections=$($case.maxConnections)"
        $args = @(
            "-ExecutionPolicy", "Bypass",
            "-File", $LinuxSmapsScript,
            "-RuntimeProfile", $RuntimeProfile,
            "-AppMode", $AppMode,
            "-JvmXss", "256k",
            "-ConcurrencyValues", ($ConcurrencyValues -join ","),
            "-EndpointSpecs", ($EndpointSpecs -join ","),
            "-DurationSeconds", "$DurationSeconds",
            "-IdleSeconds", "$IdleSeconds",
            "-FinalIdleSeconds", "$FinalIdleSeconds",
            "-HostPort", "$HostPort",
            "-ResultsDir", $runDir,
            "-ExtraJavaOpts", $extraOpts
        )
        if ($buildDone) {
            $args += "-SkipBuild"
        }

        Write-Host "Running JNI queue case: $($case.name) repeat=$repeat workers=$($case.workers) queue=$($case.queue) maxConnections=$($case.maxConnections)"
        & powershell @args
        if ($LASTEXITCODE -ne 0) {
            throw "linux_smaps_breakdown failed for case $($case.name) repeat $repeat"
        }
        $buildDone = $true

        $summary = Import-Csv (Join-Path $runDir "linux_smaps_summary.csv")
        $final = $summary | Where-Object { $_.phase -eq "99_final_idle" } | Select-Object -First 1
        $peakCurrent = ($summary | ForEach-Object { Convert-ToDouble $_.cgroup_current_mib } | Measure-Object -Maximum).Maximum
        $peakAnon = ($summary | ForEach-Object { Convert-ToDouble $_.cgroup_anon_mib } | Measure-Object -Maximum).Maximum
        foreach ($load in (Import-Csv (Join-Path $runDir "load_results.csv"))) {
            $requests = Convert-ToInt64 $load.requests
            $status503 = Get-StatusCount -StatusesJson ([string] $load.statuses) -Code "503"
            $status500 = Get-StatusCount -StatusesJson ([string] $load.statuses) -Code "500"
            $phase = "after_{0}_c{1}" -f $load.endpoint, $load.concurrency
            $metricsPath = Join-Path (Join-Path $runDir $phase) "metrics_prometheus.txt"
            $rows.Add([PSCustomObject]@{
                case = $case.name
                repeat = $repeat
                workers = $case.workers
                queue = $case.queue
                max_connections = $case.maxConnections
                endpoint = $load.endpoint
                concurrency = [int] $load.concurrency
                requests = $requests
                rps = Convert-ToDouble $load.rps
                avg_ms = Convert-ToDouble $load.avg_ms
                p95_ms = Convert-ToDouble $load.p95_ms
                p99_ms = Convert-ToDouble $load.p99_ms
                status_503 = $status503
                status_503_rate_pct = if ($requests -gt 0) { [Math]::Round($status503 * 100.0 / $requests, 3) } else { 0.0 }
                status_500 = $status500
                jni_queue_full_total = Get-PrometheusMetric -MetricsPath $metricsPath -MetricName "reactor_native_jni_queue_full_total"
                jni_workers_metric = Get-PrometheusMetric -MetricsPath $metricsPath -MetricName "reactor_native_jni_workers"
                jni_queue_capacity_metric = Get-PrometheusMetric -MetricsPath $metricsPath -MetricName "reactor_native_jni_queue_capacity"
                final_current_mib = Convert-ToDouble $final.cgroup_current_mib
                final_anon_mib = Convert-ToDouble $final.cgroup_anon_mib
                peak_current_mib = [Math]::Round($peakCurrent, 3)
                peak_anon_mib = [Math]::Round($peakAnon, 3)
                run_dir = $runDir
            })
        }
    }
}

$summaryCsv = Join-Path $ResultsDir "jni_queue_matrix_rows.csv"
$rows | Export-Csv -Path $summaryCsv -NoTypeInformation -Encoding UTF8

$aggregate = @(
    $rows |
        Group-Object case,endpoint,concurrency |
        ForEach-Object {
            $g = $_.Group
            $requests = ($g | Measure-Object requests -Sum).Sum
            $status503 = ($g | Measure-Object status_503 -Sum).Sum
            [PSCustomObject]@{
                case = $g[0].case
                workers = $g[0].workers
                queue = $g[0].queue
                max_connections = $g[0].max_connections
                endpoint = $g[0].endpoint
                concurrency = $g[0].concurrency
                avg_rps = [Math]::Round(($g | Measure-Object rps -Average).Average, 2)
                avg_p99_ms = [Math]::Round(($g | Measure-Object p99_ms -Average).Average, 2)
                max_p99_ms = [Math]::Round(($g | Measure-Object p99_ms -Maximum).Maximum, 2)
                total_requests = $requests
                total_503 = $status503
                status_503_rate_pct = if ($requests -gt 0) { [Math]::Round($status503 * 100.0 / $requests, 3) } else { 0.0 }
                total_500 = ($g | Measure-Object status_500 -Sum).Sum
                max_jni_queue_full_total = ($g | Measure-Object jni_queue_full_total -Maximum).Maximum
                avg_final_current_mib = [Math]::Round(($g | Measure-Object final_current_mib -Average).Average, 3)
                max_final_current_mib = [Math]::Round(($g | Measure-Object final_current_mib -Maximum).Maximum, 3)
                avg_final_anon_mib = [Math]::Round(($g | Measure-Object final_anon_mib -Average).Average, 3)
                max_peak_current_mib = [Math]::Round(($g | Measure-Object peak_current_mib -Maximum).Maximum, 3)
                max_peak_anon_mib = [Math]::Round(($g | Measure-Object peak_anon_mib -Maximum).Maximum, 3)
            }
        } |
        Sort-Object concurrency, status_503_rate_pct, avg_p99_ms
)
$aggregateCsv = Join-Path $ResultsDir "jni_queue_matrix_aggregate.csv"
$aggregate | Export-Csv -Path $aggregateCsv -NoTypeInformation -Encoding UTF8

$report = Join-Path $ResultsDir "jni_queue_matrix_report.md"
$lines = New-Object 'System.Collections.Generic.List[string]'
$lines.Add("# JNI Queue / Worker Matrix")
$lines.Add("")
$lines.Add("- Date: $(Get-Date -Format o)")
$lines.Add("- Runtime profile: $RuntimeProfile")
$lines.Add("- App mode: $AppMode")
$lines.Add("- Endpoint specs: $($EndpointSpecs -join ', ')")
$lines.Add("- Concurrency values: $($ConcurrencyValues -join ', ')")
$lines.Add("- Repeat count: $RepeatCount")
$lines.Add("- Duration per load phase: ${DurationSeconds}s")
$lines.Add("- Rows CSV: $summaryCsv")
$lines.Add("- Aggregate CSV: $aggregateCsv")
$lines.Add("")
$lines.Add("## Aggregate")
$lines.Add("")
$lines.Add("| Case | Workers | Queue | Max Conn | Endpoint | c | Avg RPS | Avg p99 | Max p99 | 503 % | 500 | JNI queue full max | Final current avg | Peak current max | Final anon avg |")
$lines.Add("|---|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
foreach ($row in $aggregate) {
    $lines.Add("| $($row.case) | $($row.workers) | $($row.queue) | $($row.max_connections) | $($row.endpoint) | $($row.concurrency) | $($row.avg_rps) | $($row.avg_p99_ms) ms | $($row.max_p99_ms) ms | $($row.status_503_rate_pct)% | $($row.total_500) | $($row.max_jni_queue_full_total) | $($row.avg_final_current_mib) MiB | $($row.max_peak_current_mib) MiB | $($row.avg_final_anon_mib) MiB |")
}
$lines.Add("")
$lines.Add("## Decision Rules")
$lines.Add("")
$lines.Add("- Prefer the smallest worker/queue configuration that removes c256 503 without pushing peak cgroup current above the pod budget.")
$lines.Add("- If a larger queue only hides overload by increasing p99, reject it as a default and keep it as an explicit throughput recipe.")
$lines.Add("- `max-connections` is not the primary lever unless `connections_rejected_total` is non-zero.")
$lines | Set-Content -Path $report -Encoding UTF8

Write-Output "jni queue matrix report: $report"
