param(
    [ValidateSet("micro-rest", "micro-rest-plus", "micro-rest-cpu1", "micro-rest-cpu1-no-thp", "micro-rest-cpu1-4k", "micro-rss", "ultra-low-rss", "throughput")]
    [string] $RuntimeProfile = "micro-rest",
    [ValidateSet("sample", "minimal")]
    [string] $AppMode = "minimal",
    [string[]] $XssValues = @("256k", "192k", "160k", "128k"),
    [string[]] $ConcurrencyValues = @("512"),
    [string[]] $EndpointSpecs = @(
        "small-direct|/api/v1/candidates/direct",
        "direct-heavy|/api/v1/heavy?items=100",
        "producer-heavy|/api/v1/heavy/producer?items=100",
        "dynamic-producer|/api/v1/heavy/dto?items=100",
        "raw-heavy|/api/v1/heavy/raw"
    ),
    [int] $DurationSeconds = 5,
    [int] $IdleSeconds = 2,
    [int] $FinalIdleSeconds = 20,
    [int] $HostPort = 18186,
    [string] $ExtraJavaOpts = "-Dreactor.rust.native-trim.enabled=false",
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
    $ResultsDir = Join-Path $ScriptDir ("results\xss_anon_matrix_{0}_{1}_{2}" -f $AppMode, $RuntimeProfile, (Get-Date -Format "yyyyMMdd_HHmmss"))
}
$ResultsDir = [System.IO.Path]::GetFullPath($ResultsDir)
New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null

function Convert-ToDouble {
    param($Value)
    if ($null -eq $Value) {
        return 0.0
    }
    $text = "$Value"
    if ([string]::IsNullOrWhiteSpace($text)) {
        return 0.0
    }
    $text = $text.Trim() -replace ",", "."
    return [double]::Parse($text, [System.Globalization.CultureInfo]::InvariantCulture)
}

function Convert-ToInt64 {
    param($Value)
    if ($null -eq $Value) {
        return 0L
    }
    $text = "$Value"
    if ([string]::IsNullOrWhiteSpace($text)) {
        return 0L
    }
    return [int64] $text
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

function Get-PhaseRow {
    param($Rows, [string] $Phase)
    return $Rows | Where-Object { $_.phase -eq $Phase } | Select-Object -First 1
}

function Add-FailedRow {
    param([string] $Xss, [string] $RunDir, [string] $Reason)
    return [PSCustomObject]@{
        xss = $Xss
        status = "FAIL"
        reason = $Reason
        baseline_anon_mib = 0
        final_anon_mib = 0
        final_minus_baseline_anon_mib = 0
        peak_anon_mib = 0
        baseline_current_mib = 0
        final_current_mib = 0
        peak_current_mib = 0
        final_smaps_rss_mib = 0
        final_private_dirty_mib = 0
        final_threads = 0
        final_thread_stack_budget_mib = 0
        avg_rps = 0
        avg_p99_ms = 0
        max_p99_ms = 0
        total_requests = 0
        total_503 = 0
        status_503_rate_pct = 0
        total_500 = 0
        load_errors_total = 0
        unmatched_load_errors = 0
        run_dir = $RunDir
    }
}

$rows = New-Object 'System.Collections.Generic.List[object]'
$normalizedXssValues = @(
    $XssValues |
        ForEach-Object { "$_" -split "," } |
        ForEach-Object { $_.Trim() } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
)
if ($normalizedXssValues.Count -eq 0) {
    throw "At least one Xss value is required."
}

$buildDone = [bool] $SkipBuild
foreach ($xss in $normalizedXssValues) {
    $runDir = Join-Path $ResultsDir ("xss_{0}" -f ($xss -replace '[^a-zA-Z0-9_-]+', '_'))
    $args = @(
        "-ExecutionPolicy", "Bypass",
        "-File", $LinuxSmapsScript,
        "-RuntimeProfile", $RuntimeProfile,
        "-AppMode", $AppMode,
        "-JvmXss", $xss,
        "-ConcurrencyValues", ($ConcurrencyValues -join ","),
        "-EndpointSpecs", ($EndpointSpecs -join ","),
        "-DurationSeconds", "$DurationSeconds",
        "-IdleSeconds", "$IdleSeconds",
        "-FinalIdleSeconds", "$FinalIdleSeconds",
        "-HostPort", "$HostPort",
        "-ResultsDir", $runDir
    )
    if (-not [string]::IsNullOrWhiteSpace($ExtraJavaOpts)) {
        $args += @("-ExtraJavaOpts", $ExtraJavaOpts)
    }
    if ($buildDone) {
        $args += "-SkipBuild"
    }

    Write-Host "Running Xss matrix case: $xss"
    & powershell @args
    if ($LASTEXITCODE -ne 0) {
        $rows.Add((Add-FailedRow -Xss $xss -RunDir $runDir -Reason "linux_smaps_breakdown failed"))
        continue
    }
    $buildDone = $true

    $summaryCsv = Join-Path $runDir "linux_smaps_summary.csv"
    $loadCsv = Join-Path $runDir "load_results.csv"
    if (-not (Test-Path $summaryCsv) -or -not (Test-Path $loadCsv)) {
        $rows.Add((Add-FailedRow -Xss $xss -RunDir $runDir -Reason "missing result csv"))
        continue
    }

    $summaryRows = Import-Csv $summaryCsv
    $loadRows = Import-Csv $loadCsv
    $baseline = Get-PhaseRow -Rows $summaryRows -Phase "00_baseline"
    $final = Get-PhaseRow -Rows $summaryRows -Phase "99_final_idle"
    $peakAnon = $summaryRows | Sort-Object { Convert-ToDouble $_.cgroup_anon_mib } -Descending | Select-Object -First 1
    $peakCurrent = $summaryRows | Sort-Object { Convert-ToDouble $_.cgroup_current_mib } -Descending | Select-Object -First 1

    $totalRequests = 0L
    $total503 = 0L
    $total500 = 0L
    $loadErrors = 0L
    $rpsValues = New-Object 'System.Collections.Generic.List[double]'
    $p99Values = New-Object 'System.Collections.Generic.List[double]'
    foreach ($load in $loadRows) {
        $requests = Convert-ToInt64 $load.requests
        $totalRequests += $requests
        $total503 += Get-StatusCount -StatusesJson ([string] $load.statuses) -Code "503"
        $total500 += Get-StatusCount -StatusesJson ([string] $load.statuses) -Code "500"
        $loadErrors += Convert-ToInt64 $load.errors_total
        $rpsValues.Add((Convert-ToDouble $load.rps))
        $p99Values.Add((Convert-ToDouble $load.p99_ms))
    }

    $containerLogPath = Join-Path $runDir ("rust-java-linux-smaps-{0}.log" -f $AppMode)
    $logText = ""
    if (Test-Path $containerLogPath) {
        $logText = Get-Content -Path $containerLogPath -Raw -ErrorAction SilentlyContinue
    }
    $hasStackSignal = $logText -match "StackOverflowError|unable to create native thread|OutOfMemoryError"
    $unmatchedLoadErrors = [Math]::Max(0L, $loadErrors - $total503 - $total500)
    $status = "PASS"
    $reason = "route smoke ok"
    if ($hasStackSignal -or $total500 -gt 0 -or $unmatchedLoadErrors -gt 0) {
        $status = "FAIL"
        $reason = "stack/runtime error signal"
    } elseif ($total503 -gt 0) {
        $status = "WARN"
        $reason = "route admission returned 503"
    }

    $baselineAnon = Convert-ToDouble $baseline.cgroup_anon_mib
    $finalAnon = Convert-ToDouble $final.cgroup_anon_mib
    $avgRps = 0.0
    $avgP99 = 0.0
    $maxP99 = 0.0
    if ($rpsValues.Count -gt 0) {
        $avgRps = [Math]::Round(($rpsValues | Measure-Object -Average).Average, 2)
    }
    if ($p99Values.Count -gt 0) {
        $avgP99 = [Math]::Round(($p99Values | Measure-Object -Average).Average, 3)
        $maxP99 = [Math]::Round(($p99Values | Measure-Object -Maximum).Maximum, 3)
    }
    $status503Rate = 0.0
    if ($totalRequests -gt 0) {
        $status503Rate = [Math]::Round(($total503 * 100.0) / $totalRequests, 3)
    }

    $rows.Add([PSCustomObject]@{
        xss = $xss
        status = $status
        reason = $reason
        baseline_anon_mib = $baselineAnon
        final_anon_mib = $finalAnon
        final_minus_baseline_anon_mib = [Math]::Round($finalAnon - $baselineAnon, 3)
        peak_anon_mib = Convert-ToDouble $peakAnon.cgroup_anon_mib
        baseline_current_mib = Convert-ToDouble $baseline.cgroup_current_mib
        final_current_mib = Convert-ToDouble $final.cgroup_current_mib
        peak_current_mib = Convert-ToDouble $peakCurrent.cgroup_current_mib
        final_smaps_rss_mib = Convert-ToDouble $final.smaps_rss_mib
        final_private_dirty_mib = Convert-ToDouble $final.private_dirty_mib
        final_threads = Convert-ToInt64 $final.linux_threads
        final_thread_stack_budget_mib = Convert-ToDouble $final.thread_stack_budget_mib
        avg_rps = $avgRps
        avg_p99_ms = $avgP99
        max_p99_ms = $maxP99
        total_requests = $totalRequests
        total_503 = $total503
        status_503_rate_pct = $status503Rate
        total_500 = $total500
        load_errors_total = $loadErrors
        unmatched_load_errors = $unmatchedLoadErrors
        run_dir = $runDir
    })
}

$matrixCsv = Join-Path $ResultsDir "xss_anon_matrix_summary.csv"
$rows | Export-Csv -Path $matrixCsv -NoTypeInformation -Encoding UTF8

$report = Join-Path $ResultsDir "xss_anon_matrix_report.md"
$lines = New-Object 'System.Collections.Generic.List[string]'
$lines.Add("# JVM Thread Stack Xss Anon Matrix")
$lines.Add("")
$lines.Add("- Date: $(Get-Date -Format o)")
$lines.Add("- Runtime profile: $RuntimeProfile")
$lines.Add("- App mode: $AppMode")
$lines.Add("- Xss values: $($normalizedXssValues -join ', ')")
$lines.Add("- Concurrency values: $($ConcurrencyValues -join ', ')")
$lines.Add("- Duration per load phase: ${DurationSeconds}s")
$lines.Add("- Final idle seconds: $FinalIdleSeconds")
if (-not [string]::IsNullOrWhiteSpace($ExtraJavaOpts)) {
    $lines.Add("- Extra Java opts: $ExtraJavaOpts")
}
$lines.Add("- Summary CSV: $matrixCsv")
$lines.Add("")
$lines.Add("## Result")
$lines.Add("")
$lines.Add("| Xss | Status | Reason | Baseline anon | Final anon | Peak anon | Final current | Peak current | Stack budget | Threads | Avg RPS | Avg p99 ms | Max p99 ms | 503 rate | 500 | Unmatched load errors |")
$lines.Add("|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
foreach ($row in $rows) {
    $lines.Add("| $($row.xss) | $($row.status) | $($row.reason) | $($row.baseline_anon_mib) | $($row.final_anon_mib) | $($row.peak_anon_mib) | $($row.final_current_mib) | $($row.peak_current_mib) | $($row.final_thread_stack_budget_mib) | $($row.final_threads) | $($row.avg_rps) | $($row.avg_p99_ms) | $($row.max_p99_ms) | $($row.status_503_rate_pct)% | $($row.total_500) | $($row.unmatched_load_errors) |")
}
$lines.Add("")
$lines.Add("## Gate Interpretation")
$lines.Add("")
$lines.Add("- PASS means no stack/runtime failure signal was found in route smoke logs or load status codes.")
$lines.Add("- WARN means the process survived, but route admission returned 503 under the selected concurrency.")
$lines.Add("- FAIL means StackOverflowError/native-thread/OOM/500/unmatched-load-error evidence appeared and the Xss value should not be used as a default.")
$lines.Add("- Smaller Xss reduces the theoretical stack budget. It only reduces Kubernetes RSS if those stack pages were resident; reserved stack size alone is not equivalent to anon RSS.")
$lines.Add("- Do not make an Xss value a production default unless this matrix and the real service's deepest route/RPC/JDBC call stack both pass.")
$lines.Add("")
$lines.Add("## Run Directories")
$lines.Add("")
foreach ($row in $rows) {
    $lines.Add("- $($row.xss): $($row.run_dir)")
}
$lines | Set-Content -Path $report -Encoding UTF8

Write-Output "xss anon matrix report: $report"
