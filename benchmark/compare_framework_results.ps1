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
    [switch] $FailOnGate
)

$ErrorActionPreference = "Stop"

function Convert-ToDoubleValue {
    param($Value)

    if ($null -eq $Value -or [string]::IsNullOrWhiteSpace("$Value")) {
        return 0.0
    }
    return [double](("$Value" -replace "ms", "") -replace ",", ".")
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

            [PSCustomObject]@{
                endpoint_class = $first.EndpointClass
                endpoint = $first.Endpoint
                concurrency = [int] $first.Concurrency
                runs = $rows.Count
                avg_rps = ($runMetrics | Measure-Object -Property rps -Average).Average
                avg_useful_200_rps = ($runMetrics | Measure-Object -Property useful_200_rps -Average).Average
                avg_p99_ms = ($runMetrics | Measure-Object -Property p99_ms -Average).Average
                max_p99_ms = ($runMetrics | Measure-Object -Property p99_ms -Maximum).Maximum
                avg_rss_after_mib = ($runMetrics | Measure-Object -Property rss_after_mib -Average).Average
                avg_max_container_mem_mib = ($runMetrics | Measure-Object -Property max_container_mem_mib -Average).Average
                max_container_mem_mib = ($runMetrics | Measure-Object -Property max_container_mem_mib -Maximum).Maximum
                status_503_rate_pct = if ($statusTotal -gt 0) { 100.0 * $status503Total / $statusTotal } else { 0.0 }
            }
        }
}

$baseline = @(Get-ResultsSummary -ResultsDir $BaselineResultsDir)
$candidate = @(Get-ResultsSummary -ResultsDir $CandidateResultsDir)

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

    $usefulDeltaPct = Get-PercentDelta -Baseline $base.avg_useful_200_rps -Candidate $next.avg_useful_200_rps
    $p99DeltaPct = Get-PercentDelta -Baseline $base.avg_p99_ms -Candidate $next.avg_p99_ms
    $memoryDelta = $next.avg_max_container_mem_mib - $base.avg_max_container_mem_mib
    $rejectDelta = $next.status_503_rate_pct - $base.status_503_rate_pct
    $strict = $base.concurrency -in $StrictConcurrencyLevels
    $failures = New-Object System.Collections.Generic.List[string]
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
        runs = $base.runs
        baseline_useful_200_rps = [math]::Round($base.avg_useful_200_rps, 2)
        candidate_useful_200_rps = [math]::Round($next.avg_useful_200_rps, 2)
        useful_200_rps_delta_pct = [math]::Round($usefulDeltaPct, 2)
        baseline_p99_ms = [math]::Round($base.avg_p99_ms, 2)
        candidate_p99_ms = [math]::Round($next.avg_p99_ms, 2)
        p99_delta_pct = [math]::Round($p99DeltaPct, 2)
        baseline_503_pct = [math]::Round($base.status_503_rate_pct, 2)
        candidate_503_pct = [math]::Round($next.status_503_rate_pct, 2)
        status_503_delta_pp = [math]::Round($rejectDelta, 2)
        baseline_avg_max_mem_mib = [math]::Round($base.avg_max_container_mem_mib, 2)
        candidate_avg_max_mem_mib = [math]::Round($next.avg_max_container_mem_mib, 2)
        memory_delta_mib = [math]::Round($memoryDelta, 2)
        strict_gate = $strict
        gate = if ($failures.Count -eq 0) { "PASS" } else { "FAIL: $($failures -join ',')" }
    }
}

$comparison = @($comparison | Sort-Object endpoint_class, concurrency)
$csvOutput = Join-Path $OutputDir "comparison.csv"
$comparison | Export-Csv -LiteralPath $csvOutput -NoTypeInformation -Encoding utf8

$strictFailures = @($comparison | Where-Object { $_.strict_gate -and $_.gate -ne "PASS" })
$report = New-Object System.Collections.Generic.List[string]
$report.Add("# Framework Image A/B Comparison")
$report.Add("")
$report.Add("- Baseline: $([System.IO.Path]::GetFullPath($BaselineResultsDir))")
$report.Add("- Candidate: $([System.IO.Path]::GetFullPath($CandidateResultsDir))")
$report.Add("- Strict concurrency levels: $($StrictConcurrencyLevels -join ', ')")
$report.Add("- Gate result: $(if ($strictFailures.Count -eq 0) { 'PASS' } else { 'FAIL' })")
$report.Add("- Useful 200 RPS threshold: >= $MinUsefulRpsDeltaPercent% delta")
$report.Add("- P99 threshold: <= $MaxP99RegressionPercent% regression")
$report.Add("- 503 threshold: <= $Max503DeltaPercentagePoints percentage-point increase")
$report.Add("- Memory threshold: <= $MaxMemoryRegressionMiB MiB increase")
$report.Add("")
$report.Add("| Class | C | Baseline useful 200 RPS | Candidate useful 200 RPS | Delta | Baseline p99 ms | Candidate p99 ms | Delta | Baseline 503 | Candidate 503 | Memory delta MiB | Gate |")
$report.Add("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|")
foreach ($row in $comparison) {
    $report.Add("| $($row.endpoint_class) | $($row.concurrency) | $($row.baseline_useful_200_rps) | $($row.candidate_useful_200_rps) | $($row.useful_200_rps_delta_pct)% | $($row.baseline_p99_ms) | $($row.candidate_p99_ms) | $($row.p99_delta_pct)% | $($row.baseline_503_pct)% | $($row.candidate_503_pct)% | $($row.memory_delta_mib) | $($row.gate) |")
}
$reportPath = Join-Path $OutputDir "comparison.md"
$report | Set-Content -LiteralPath $reportPath -Encoding utf8

Write-Host "Comparison report: $reportPath"
Write-Host "Gate result: $(if ($strictFailures.Count -eq 0) { 'PASS' } else { 'FAIL' })"
if ($FailOnGate -and $strictFailures.Count -gt 0) {
    exit 2
}
