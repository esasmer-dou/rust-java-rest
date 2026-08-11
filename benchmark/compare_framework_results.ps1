param(
    [Parameter(Mandatory = $true)]
    [string] $BaselineResultsDir,

    [Parameter(Mandatory = $true)]
    [string] $CandidateResultsDir,

    [string] $OutputDir = "",
    [int[]] $StrictConcurrencyLevels = @(64, 256),
    [double] $MinUsefulRpsDeltaPercent = -2.0,
    [double] $MaxP99RegressionPercent = 10.0,
    [double] $Max503DeltaPercentagePoints = 2.0,
    [double] $MaxMemoryRegressionMiB = 1.0,
    [int] $MinStrictRuns = 3,
    [double] $MaxUsefulRpsCoefficientVariationPercent = 10.0,
    [double] $MaxP99CoefficientVariationPercent = 15.0,
    [double] $MaxStartupRegressionPercent = 10.0,
    [double] $MaxStartupCoefficientVariationPercent = 15.0,
    [switch] $FailOnGate
)

$ErrorActionPreference = "Stop"

function Convert-ToDoubleValue {
    param($Value)

    if ($null -eq $Value -or [string]::IsNullOrWhiteSpace("$Value")) {
        return 0.0
    }

    $normalized = ("$Value").Trim().ToLowerInvariant() -replace ",", "."
    $match = [regex]::Match(
        $normalized,
        "^([-+]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+))\s*(ns|us|µs|ms|s|m)?$"
    )
    if (-not $match.Success) {
        throw "Unsupported numeric benchmark value: '$Value'"
    }

    $number = [double]::Parse($match.Groups[1].Value, [System.Globalization.CultureInfo]::InvariantCulture)
    switch ($match.Groups[2].Value) {
        "ns" { return $number / 1000000.0 }
        "us" { return $number / 1000.0 }
        "µs" { return $number / 1000.0 }
        "s"  { return $number * 1000.0 }
        "m"  { return $number * 60000.0 }
        default { return $number }
    }
}

function Get-StatusCount {
    param([string] $StatusText, [string] $Code)

    if ([string]::IsNullOrWhiteSpace($StatusText)) {
        return [int64] 0
    }
    $match = [regex]::Match($StatusText, "(?:^|[, ]+)$([regex]::Escape($Code))=([0-9]+)")
    if ($match.Success) {
        return [int64] $match.Groups[1].Value
    }
    return [int64] 0
}

function Get-PercentDelta {
    param([double] $Baseline, [double] $Candidate)

    if ($Baseline -eq 0.0) {
        return 0.0
    }
    return 100.0 * ($Candidate - $Baseline) / $Baseline
}

function Get-MedianValue {
    param([object[]] $Values)

    $sorted = @($Values | ForEach-Object { [double] $_ } | Sort-Object)
    if ($sorted.Count -eq 0) {
        return 0.0
    }
    $middle = [int] [Math]::Floor($sorted.Count / 2)
    if (($sorted.Count % 2) -eq 1) {
        return $sorted[$middle]
    }
    return ($sorted[$middle - 1] + $sorted[$middle]) / 2.0
}

function Get-CoefficientVariationPercent {
    param([object[]] $Values)

    $numbers = @($Values | ForEach-Object { [double] $_ })
    if ($numbers.Count -lt 2) {
        return 0.0
    }
    $average = ($numbers | Measure-Object -Average).Average
    if ([Math]::Abs($average) -lt 0.0000001) {
        return 0.0
    }
    $sumSquared = 0.0
    foreach ($number in $numbers) {
        $delta = $number - $average
        $sumSquared += $delta * $delta
    }
    $standardDeviation = [Math]::Sqrt($sumSquared / $numbers.Count)
    return 100.0 * $standardDeviation / [Math]::Abs($average)
}

function Get-ResultsSummary {
    param([string] $ResultsDir)

    $csvPath = Join-Path ([System.IO.Path]::GetFullPath($ResultsDir)) "results.csv"
    if (-not (Test-Path -LiteralPath $csvPath)) {
        throw "Missing benchmark results: $csvPath"
    }

    @(Import-Csv -LiteralPath $csvPath) |
        Group-Object EndpointClass, Endpoint, Concurrency |
        ForEach-Object {
            $rows = @($_.Group)
            $first = $rows[0]
            $runMetrics = foreach ($row in $rows) {
                $status200 = Get-StatusCount -StatusText $row.HttpStatus -Code "200"
                $status503 = Get-StatusCount -StatusText $row.HttpStatus -Code "503"
                $statusTotal = $status200 + $status503
                $rps = Convert-ToDoubleValue $row.Rps
                [PSCustomObject]@{
                    rps = $rps
                    useful_200_rps = if ($statusTotal -gt 0) { $rps * $status200 / $statusTotal } else { $rps }
                    p99_ms = Convert-ToDoubleValue $row.P99
                    rss_after_mib = Convert-ToDoubleValue $row.RssAfterMiB
                    max_container_mem_mib = Convert-ToDoubleValue $row.MaxContainerMemMiB
                    status_200 = $status200
                    status_503 = $status503
                }
            }
            $status200Total = ($runMetrics | Measure-Object -Property status_200 -Sum).Sum
            $status503Total = ($runMetrics | Measure-Object -Property status_503 -Sum).Sum
            $statusTotal = $status200Total + $status503Total
            $usefulRpsValues = @($runMetrics | ForEach-Object { $_.useful_200_rps })
            $p99Values = @($runMetrics | ForEach-Object { $_.p99_ms })

            [PSCustomObject]@{
                endpoint_class = $first.EndpointClass
                endpoint = $first.Endpoint
                concurrency = [int] $first.Concurrency
                runs = $rows.Count
                avg_rps = ($runMetrics | Measure-Object -Property rps -Average).Average
                avg_useful_200_rps = ($runMetrics | Measure-Object -Property useful_200_rps -Average).Average
                median_useful_200_rps = Get-MedianValue -Values $usefulRpsValues
                useful_200_rps_cv_pct = Get-CoefficientVariationPercent -Values $usefulRpsValues
                avg_p99_ms = ($runMetrics | Measure-Object -Property p99_ms -Average).Average
                median_p99_ms = Get-MedianValue -Values $p99Values
                p99_cv_pct = Get-CoefficientVariationPercent -Values $p99Values
                max_p99_ms = ($runMetrics | Measure-Object -Property p99_ms -Maximum).Maximum
                avg_rss_after_mib = ($runMetrics | Measure-Object -Property rss_after_mib -Average).Average
                avg_max_container_mem_mib = ($runMetrics | Measure-Object -Property max_container_mem_mib -Average).Average
                max_container_mem_mib = ($runMetrics | Measure-Object -Property max_container_mem_mib -Maximum).Maximum
                status_503_rate_pct = if ($statusTotal -gt 0) { 100.0 * $status503Total / $statusTotal } else { 0.0 }
            }
        }
}

function Get-StartupSummary {
    param([string] $ResultsDir)

    $csvPath = Join-Path ([System.IO.Path]::GetFullPath($ResultsDir)) "results.csv"
    $rows = @(Import-Csv -LiteralPath $csvPath | Where-Object {
        $_.PSObject.Properties.Name -contains "StartupReadyMs" -and
        -not [string]::IsNullOrWhiteSpace($_.StartupReadyMs) -and
        [double]$_.StartupReadyMs -ge 0
    })
    if ($rows.Count -eq 0) {
        return $null
    }

    $hasPairIdentity = $rows[0].PSObject.Properties.Name -contains "PairCycle" -and
            $rows[0].PSObject.Properties.Name -contains "PairPosition"
    $samples = if ($hasPairIdentity) {
        @($rows | Group-Object PairCycle, PairPosition | ForEach-Object { $_.Group[0] })
    } else {
        @($rows | Select-Object -First 1)
    }
    $readyValues = @($samples | ForEach-Object { [double]$_.StartupReadyMs })
    $reachableValues = @($samples | ForEach-Object { [double]$_.StartupReachableMs })
    return [PSCustomObject]@{
        runs = $samples.Count
        median_ready_ms = Get-MedianValue -Values $readyValues
        ready_cv_pct = Get-CoefficientVariationPercent -Values $readyValues
        median_reachable_ms = Get-MedianValue -Values $reachableValues
        reachable_cv_pct = Get-CoefficientVariationPercent -Values $reachableValues
    }
}

$baseline = @(Get-ResultsSummary -ResultsDir $BaselineResultsDir)
$candidate = @(Get-ResultsSummary -ResultsDir $CandidateResultsDir)
$baselineStartup = Get-StartupSummary -ResultsDir $BaselineResultsDir
$candidateStartup = Get-StartupSummary -ResultsDir $CandidateResultsDir

if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path ([System.IO.Path]::GetFullPath($CandidateResultsDir)) "comparison"
}
$OutputDir = [System.IO.Path]::GetFullPath($OutputDir)
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$comparison = foreach ($base in $baseline) {
    $next = $candidate |
        Where-Object {
            $_.endpoint_class -eq $base.endpoint_class -and
            $_.endpoint -eq $base.endpoint -and
            $_.concurrency -eq $base.concurrency
        } |
        Select-Object -First 1
    if ($null -eq $next) {
        throw "Candidate result is missing $($base.endpoint_class)/$($base.endpoint)/c$($base.concurrency)."
    }

    # Release decisions use medians. Averages remain in the CSV for capacity analysis, but one
    # scheduler/JIT outlier must not be misreported as a deterministic framework regression.
    $usefulDeltaPct = Get-PercentDelta `
            -Baseline $base.median_useful_200_rps `
            -Candidate $next.median_useful_200_rps
    $p99DeltaPct = Get-PercentDelta -Baseline $base.median_p99_ms -Candidate $next.median_p99_ms
    $memoryDelta = $next.avg_max_container_mem_mib - $base.avg_max_container_mem_mib
    $rejectDelta = $next.status_503_rate_pct - $base.status_503_rate_pct
    $strict = $base.concurrency -in $StrictConcurrencyLevels
    $failures = New-Object System.Collections.Generic.List[string]
    $inconclusive = New-Object System.Collections.Generic.List[string]
    if ($strict -and ($base.runs -lt $MinStrictRuns -or $next.runs -lt $MinStrictRuns)) {
        $inconclusive.Add("insufficient-runs")
    }
    if ($strict -and ($base.useful_200_rps_cv_pct -gt $MaxUsefulRpsCoefficientVariationPercent `
            -or $next.useful_200_rps_cv_pct -gt $MaxUsefulRpsCoefficientVariationPercent)) {
        $inconclusive.Add("unstable-useful-rps")
    }
    if ($strict -and ($base.p99_cv_pct -gt $MaxP99CoefficientVariationPercent `
            -or $next.p99_cv_pct -gt $MaxP99CoefficientVariationPercent)) {
        $inconclusive.Add("unstable-p99")
    }
    if ($strict -and $usefulDeltaPct -lt $MinUsefulRpsDeltaPercent) {
        $failures.Add("useful-rps")
    }
    if ($strict -and $p99DeltaPct -gt $MaxP99RegressionPercent) {
        $failures.Add("p99")
    }
    if ($strict -and $rejectDelta -gt $Max503DeltaPercentagePoints) {
        $failures.Add("503-rate")
    }
    if ($memoryDelta -gt $MaxMemoryRegressionMiB) {
        $failures.Add("memory")
    }

    [PSCustomObject]@{
        endpoint_class = $base.endpoint_class
        endpoint = $base.endpoint
        concurrency = $base.concurrency
        baseline_runs = $base.runs
        candidate_runs = $next.runs
        baseline_useful_200_rps = [math]::Round($base.median_useful_200_rps, 2)
        candidate_useful_200_rps = [math]::Round($next.median_useful_200_rps, 2)
        useful_200_rps_delta_pct = [math]::Round($usefulDeltaPct, 2)
        baseline_useful_rps_cv_pct = [math]::Round($base.useful_200_rps_cv_pct, 2)
        candidate_useful_rps_cv_pct = [math]::Round($next.useful_200_rps_cv_pct, 2)
        baseline_p99_ms = [math]::Round($base.median_p99_ms, 2)
        candidate_p99_ms = [math]::Round($next.median_p99_ms, 2)
        p99_delta_pct = [math]::Round($p99DeltaPct, 2)
        baseline_p99_cv_pct = [math]::Round($base.p99_cv_pct, 2)
        candidate_p99_cv_pct = [math]::Round($next.p99_cv_pct, 2)
        baseline_503_pct = [math]::Round($base.status_503_rate_pct, 2)
        candidate_503_pct = [math]::Round($next.status_503_rate_pct, 2)
        status_503_delta_pp = [math]::Round($rejectDelta, 2)
        baseline_avg_max_mem_mib = [math]::Round($base.avg_max_container_mem_mib, 2)
        candidate_avg_max_mem_mib = [math]::Round($next.avg_max_container_mem_mib, 2)
        memory_delta_mib = [math]::Round($memoryDelta, 2)
        strict_gate = $strict
        gate = if ($inconclusive.Count -gt 0) {
            "INCONCLUSIVE: $($inconclusive -join ',')"
        } elseif ($failures.Count -eq 0) {
            "PASS"
        } else {
            "FAIL: $($failures -join ',')"
        }
    }
}

$comparison = @($comparison | Sort-Object endpoint_class, concurrency)
$csvOutput = Join-Path $OutputDir "comparison.csv"
$comparison | Export-Csv -LiteralPath $csvOutput -NoTypeInformation -Encoding utf8

$startupGate = "NOT MEASURED"
$startupComparison = $null
if ($null -ne $baselineStartup -or $null -ne $candidateStartup) {
    if ($null -eq $baselineStartup -or $null -eq $candidateStartup) {
        $startupGate = "INCONCLUSIVE: missing-startup-samples"
    } else {
        $readyDelta = Get-PercentDelta `
                -Baseline $baselineStartup.median_ready_ms `
                -Candidate $candidateStartup.median_ready_ms
        $reachableDelta = Get-PercentDelta `
                -Baseline $baselineStartup.median_reachable_ms `
                -Candidate $candidateStartup.median_reachable_ms
        $startupFailures = New-Object System.Collections.Generic.List[string]
        $startupInconclusive = New-Object System.Collections.Generic.List[string]
        if ($baselineStartup.runs -lt $MinStrictRuns -or $candidateStartup.runs -lt $MinStrictRuns) {
            $startupInconclusive.Add("insufficient-runs")
        }
        if ($baselineStartup.ready_cv_pct -gt $MaxStartupCoefficientVariationPercent `
                -or $candidateStartup.ready_cv_pct -gt $MaxStartupCoefficientVariationPercent `
                -or $baselineStartup.reachable_cv_pct -gt $MaxStartupCoefficientVariationPercent `
                -or $candidateStartup.reachable_cv_pct -gt $MaxStartupCoefficientVariationPercent) {
            $startupInconclusive.Add("unstable-startup")
        }
        if ($readyDelta -gt $MaxStartupRegressionPercent) {
            $startupFailures.Add("internal-ready")
        }
        if ($reachableDelta -gt $MaxStartupRegressionPercent) {
            $startupFailures.Add("http-reachable")
        }
        $startupGate = if ($startupInconclusive.Count -gt 0) {
            "INCONCLUSIVE: $($startupInconclusive -join ',')"
        } elseif ($startupFailures.Count -gt 0) {
            "FAIL: $($startupFailures -join ',')"
        } else {
            "PASS"
        }
        $startupComparison = [PSCustomObject]@{
            baseline_runs = $baselineStartup.runs
            candidate_runs = $candidateStartup.runs
            baseline_median_ready_ms = [math]::Round($baselineStartup.median_ready_ms, 2)
            candidate_median_ready_ms = [math]::Round($candidateStartup.median_ready_ms, 2)
            ready_delta_pct = [math]::Round($readyDelta, 2)
            baseline_ready_cv_pct = [math]::Round($baselineStartup.ready_cv_pct, 2)
            candidate_ready_cv_pct = [math]::Round($candidateStartup.ready_cv_pct, 2)
            baseline_median_reachable_ms = [math]::Round($baselineStartup.median_reachable_ms, 2)
            candidate_median_reachable_ms = [math]::Round($candidateStartup.median_reachable_ms, 2)
            reachable_delta_pct = [math]::Round($reachableDelta, 2)
            baseline_reachable_cv_pct = [math]::Round($baselineStartup.reachable_cv_pct, 2)
            candidate_reachable_cv_pct = [math]::Round($candidateStartup.reachable_cv_pct, 2)
            gate = $startupGate
        }
        $startupComparison | Export-Csv -LiteralPath (Join-Path $OutputDir "startup_comparison.csv") `
                -NoTypeInformation -Encoding utf8
    }
}

$strictBlockers = @($comparison | Where-Object { $_.strict_gate -and $_.gate -ne "PASS" })
$startupBlocked = $startupGate -notin @("PASS", "NOT MEASURED")
$gatePassed = $strictBlockers.Count -eq 0 -and -not $startupBlocked
$report = New-Object System.Collections.Generic.List[string]
$report.Add("# Framework Image A/B Comparison")
$report.Add("")
$report.Add("- Baseline: $([System.IO.Path]::GetFullPath($BaselineResultsDir))")
$report.Add("- Candidate: $([System.IO.Path]::GetFullPath($CandidateResultsDir))")
$report.Add("- Strict concurrency levels: $($StrictConcurrencyLevels -join ', ')")
$report.Add("- Gate result: $(if ($gatePassed) { 'PASS' } else { 'BLOCKED' })")
$report.Add("- Minimum strict runs per image: $MinStrictRuns")
$report.Add("- Useful 200 RPS decision statistic: median; threshold >= $MinUsefulRpsDeltaPercent% delta")
$report.Add("- P99 decision statistic: median; threshold <= $MaxP99RegressionPercent% regression")
$report.Add("- Stability thresholds: useful RPS CV <= $MaxUsefulRpsCoefficientVariationPercent%; p99 CV <= $MaxP99CoefficientVariationPercent%")
$report.Add("- 503 threshold: <= $Max503DeltaPercentagePoints percentage-point increase")
$report.Add("- Memory threshold: <= $MaxMemoryRegressionMiB MiB increase")
$report.Add("- Startup threshold: <= $MaxStartupRegressionPercent% regression; CV <= $MaxStartupCoefficientVariationPercent%")
$report.Add("- Startup gate: $startupGate")
$report.Add("")
if ($null -ne $startupComparison) {
    $report.Add("| Startup | Runs B/C | Baseline median ms | Candidate median ms | Delta | CV B/C | Gate |")
    $report.Add("|---|---:|---:|---:|---:|---:|---|")
    $report.Add("| Internal ready | $($startupComparison.baseline_runs)/$($startupComparison.candidate_runs) | $($startupComparison.baseline_median_ready_ms) | $($startupComparison.candidate_median_ready_ms) | $($startupComparison.ready_delta_pct)% | $($startupComparison.baseline_ready_cv_pct)%/$($startupComparison.candidate_ready_cv_pct)% | $startupGate |")
    $report.Add("| HTTP reachable | $($startupComparison.baseline_runs)/$($startupComparison.candidate_runs) | $($startupComparison.baseline_median_reachable_ms) | $($startupComparison.candidate_median_reachable_ms) | $($startupComparison.reachable_delta_pct)% | $($startupComparison.baseline_reachable_cv_pct)%/$($startupComparison.candidate_reachable_cv_pct)% | $startupGate |")
    $report.Add("")
}
$report.Add("")
$report.Add("| Class | C | Runs B/C | Baseline median useful RPS | Candidate median useful RPS | Delta | RPS CV B/C | Baseline median p99 ms | Candidate median p99 ms | Delta | p99 CV B/C | 503 B/C | Memory delta MiB | Gate |")
$report.Add("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|")
foreach ($row in $comparison) {
    $report.Add("| $($row.endpoint_class) | $($row.concurrency) | $($row.baseline_runs)/$($row.candidate_runs) | $($row.baseline_useful_200_rps) | $($row.candidate_useful_200_rps) | $($row.useful_200_rps_delta_pct)% | $($row.baseline_useful_rps_cv_pct)%/$($row.candidate_useful_rps_cv_pct)% | $($row.baseline_p99_ms) | $($row.candidate_p99_ms) | $($row.p99_delta_pct)% | $($row.baseline_p99_cv_pct)%/$($row.candidate_p99_cv_pct)% | $($row.baseline_503_pct)%/$($row.candidate_503_pct)% | $($row.memory_delta_mib) | $($row.gate) |")
}
$reportPath = Join-Path $OutputDir "comparison.md"
$report | Set-Content -LiteralPath $reportPath -Encoding utf8

Write-Host "Comparison report: $reportPath"
Write-Host "Gate result: $(if ($gatePassed) { 'PASS' } else { 'BLOCKED' })"
if ($FailOnGate -and -not $gatePassed) {
    exit 2
}
