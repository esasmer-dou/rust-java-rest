param(
    [Parameter(Mandatory = $true)]
    [string] $PhaseAbDir,

    [Parameter(Mandatory = $true)]
    [string] $PhaseBaDir,

    [Parameter(Mandatory = $true)]
    [string] $OutputDir,

    [int] $MinRuns = 6,
    [double] $MinUsefulRpsDeltaPercent = -2.0,
    [double] $MaxP99RegressionPercent = 10.0,
    [double] $Max503DeltaPercentagePoints = 2.0,
    [double] $MaxMemoryRegressionMiB = 1.0,
    [double] $MaxRpsPairDeltaStandardDeviation = 10.0,
    [double] $MaxP99PairDeltaStandardDeviation = 15.0,
    [switch] $FailOnGate
)

$ErrorActionPreference = "Stop"

function Convert-ToDoubleValue {
    param([object] $Value)
    if ($null -eq $Value -or [string]::IsNullOrWhiteSpace("$Value")) {
        return 0.0
    }
    $normalized = "$Value".Trim().Replace(",", ".")
    return [double]::Parse(
            $normalized,
            [System.Globalization.NumberStyles]::Float,
            [System.Globalization.CultureInfo]::InvariantCulture)
}

function Convert-ToMilliseconds {
    param([object] $Value)
    $text = "$Value".Trim()
    if ($text -notmatch '^([0-9.,]+)\s*(ns|us|µs|ms|s)$') {
        throw "Unsupported latency value: $Value"
    }
    $number = Convert-ToDoubleValue $Matches[1]
    $milliseconds = switch ($Matches[2]) {
        "ns" { $number / 1000000.0 }
        "us" { $number / 1000.0 }
        "µs" { $number / 1000.0 }
        "s" { $number * 1000.0 }
        default { $number }
    }
    return $milliseconds
}

function Get-StatusCounts {
    param([string] $Text)
    $counts = @{}
    foreach ($match in [regex]::Matches("$Text", '(?:^|,\s*)([0-9]{3})=([0-9]+)')) {
        $counts[$match.Groups[1].Value] = [int64] $match.Groups[2].Value
    }
    return $counts
}

function Get-UsefulRps {
    param([object] $Row)
    $counts = Get-StatusCounts $Row.HttpStatus
    $total = 0L
    foreach ($count in $counts.Values) {
        $total += $count
    }
    $status200 = if ($counts.ContainsKey("200")) { $counts["200"] } else { 0L }
    $rps = Convert-ToDoubleValue $Row.Rps
    if ($total -gt 0) {
        return $rps * $status200 / $total
    }
    return $rps
}

function Get-503Rate {
    param([object] $Row)
    $counts = Get-StatusCounts $Row.HttpStatus
    $total = 0L
    foreach ($count in $counts.Values) {
        $total += $count
    }
    $status503 = if ($counts.ContainsKey("503")) { $counts["503"] } else { 0L }
    if ($total -gt 0) {
        return 100.0 * $status503 / $total
    }
    return 0.0
}

function Get-PercentDelta {
    param([double] $Baseline, [double] $Candidate)
    if ($Baseline -eq 0) {
        return 0.0
    }
    return 100.0 * ($Candidate - $Baseline) / $Baseline
}

function Get-Median {
    param([object[]] $Values)
    $numbers = @($Values | ForEach-Object { [double] $_ } | Sort-Object)
    if ($numbers.Count -eq 0) {
        return 0.0
    }
    if (($numbers.Count % 2) -eq 1) {
        return $numbers[[int] [math]::Floor($numbers.Count / 2)]
    }
    $upper = [int] ($numbers.Count / 2)
    return ($numbers[$upper - 1] + $numbers[$upper]) / 2.0
}

function Get-StandardDeviation {
    param([object[]] $Values)
    $numbers = @($Values | ForEach-Object { [double] $_ })
    if ($numbers.Count -lt 2) {
        return 0.0
    }
    $average = ($numbers | Measure-Object -Average).Average
    $sum = 0.0
    foreach ($number in $numbers) {
        $sum += [math]::Pow($number - $average, 2)
    }
    return [math]::Sqrt($sum / $numbers.Count)
}

function Get-RowKey {
    param([object] $Row)
    return "$($Row.EndpointClass)|$($Row.Endpoint)|$($Row.Concurrency)|$($Row.Run)"
}

function Get-PairedRows {
    param(
        [object[]] $BaselineRows,
        [object[]] $CandidateRows,
        [string] $Phase
    )
    $candidateByKey = @{}
    foreach ($candidateRow in $CandidateRows) {
        $candidateByKey[(Get-RowKey $candidateRow)] = $candidateRow
    }
    foreach ($baselineRow in $BaselineRows) {
        $key = Get-RowKey $baselineRow
        if (-not $candidateByKey.ContainsKey($key)) {
            throw "Missing candidate crossover row: $Phase/$key"
        }
        $candidateRow = $candidateByKey[$key]
        $baselineUsefulRps = Get-UsefulRps $baselineRow
        $candidateUsefulRps = Get-UsefulRps $candidateRow
        $baselineP99 = Convert-ToMilliseconds $baselineRow.P99
        $candidateP99 = Convert-ToMilliseconds $candidateRow.P99
        $baseline503 = Get-503Rate $baselineRow
        $candidate503 = Get-503Rate $candidateRow
        $baselineMemory = Convert-ToDoubleValue $baselineRow.MaxContainerMemMiB
        $candidateMemory = Convert-ToDoubleValue $candidateRow.MaxContainerMemMiB
        [PSCustomObject]@{
            Phase = $Phase
            EndpointClass = $baselineRow.EndpointClass
            Endpoint = $baselineRow.Endpoint
            Concurrency = [int] $baselineRow.Concurrency
            Run = [int] $baselineRow.Run
            BaselineUsefulRps = $baselineUsefulRps
            CandidateUsefulRps = $candidateUsefulRps
            RpsDeltaPct = Get-PercentDelta $baselineUsefulRps $candidateUsefulRps
            BaselineP99Ms = $baselineP99
            CandidateP99Ms = $candidateP99
            P99DeltaPct = Get-PercentDelta $baselineP99 $candidateP99
            Status503DeltaPp = $candidate503 - $baseline503
            MemoryDeltaMiB = $candidateMemory - $baselineMemory
        }
    }
}

$phaseAbBaseline = @(Import-Csv -LiteralPath (Join-Path $PhaseAbDir "baseline\results.csv"))
$phaseAbCandidate = @(Import-Csv -LiteralPath (Join-Path $PhaseAbDir "candidate\results.csv"))
$phaseBaCandidateImage = @(Import-Csv -LiteralPath (Join-Path $PhaseBaDir "baseline\results.csv"))
$phaseBaBaselineImage = @(Import-Csv -LiteralPath (Join-Path $PhaseBaDir "candidate\results.csv"))

# Phase BA was launched with images swapped. Normalize it back to baseline-image -> candidate-image.
$pairs = @(
    Get-PairedRows -BaselineRows $phaseAbBaseline -CandidateRows $phaseAbCandidate -Phase "AB"
) + @(
    Get-PairedRows -BaselineRows $phaseBaBaselineImage -CandidateRows $phaseBaCandidateImage -Phase "BA"
)

$comparison = @($pairs |
    Group-Object EndpointClass, Endpoint, Concurrency |
    ForEach-Object {
        $rows = @($_.Group)
        $first = $rows[0]
        $rpsDeltas = @($rows | ForEach-Object RpsDeltaPct)
        $p99Deltas = @($rows | ForEach-Object P99DeltaPct)
        $statusDeltas = @($rows | ForEach-Object Status503DeltaPp)
        $memoryDeltas = @($rows | ForEach-Object MemoryDeltaMiB)
        $failures = [System.Collections.Generic.List[string]]::new()
        $inconclusive = [System.Collections.Generic.List[string]]::new()
        $medianRpsDelta = Get-Median $rpsDeltas
        $medianP99Delta = Get-Median $p99Deltas
        $median503Delta = Get-Median $statusDeltas
        $medianMemoryDelta = Get-Median $memoryDeltas
        $rpsDeltaSd = Get-StandardDeviation $rpsDeltas
        $p99DeltaSd = Get-StandardDeviation $p99Deltas
        $baselineUsefulMedian = Get-Median @($rows | ForEach-Object BaselineUsefulRps)
        $candidateUsefulMedian = Get-Median @($rows | ForEach-Object CandidateUsefulRps)
        $baselineP99Median = Get-Median @($rows | ForEach-Object BaselineP99Ms)
        $candidateP99Median = Get-Median @($rows | ForEach-Object CandidateP99Ms)
        $decisionRpsDelta = Get-PercentDelta $baselineUsefulMedian $candidateUsefulMedian
        $decisionP99Delta = Get-PercentDelta $baselineP99Median $candidateP99Median

        if ($rows.Count -lt $MinRuns) {
            $inconclusive.Add("insufficient-pairs")
        }
        if ($rpsDeltaSd -gt $MaxRpsPairDeltaStandardDeviation) {
            $inconclusive.Add("unstable-paired-rps")
        }
        if ($p99DeltaSd -gt $MaxP99PairDeltaStandardDeviation) {
            $inconclusive.Add("unstable-paired-p99")
        }
        if ($decisionRpsDelta -lt $MinUsefulRpsDeltaPercent) {
            $failures.Add("useful-rps")
        }
        if ($decisionP99Delta -gt $MaxP99RegressionPercent) {
            $failures.Add("p99")
        }
        if ($median503Delta -gt $Max503DeltaPercentagePoints) {
            $failures.Add("503-rate")
        }
        if ($medianMemoryDelta -gt $MaxMemoryRegressionMiB) {
            $failures.Add("memory")
        }

        [PSCustomObject]@{
            endpoint_class = $first.EndpointClass
            endpoint = $first.Endpoint
            concurrency = $first.Concurrency
            pairs = $rows.Count
            baseline_median_useful_200_rps = [math]::Round($baselineUsefulMedian, 2)
            candidate_median_useful_200_rps = [math]::Round($candidateUsefulMedian, 2)
            useful_200_rps_delta_pct = [math]::Round($decisionRpsDelta, 2)
            median_paired_rps_delta_pct = [math]::Round($medianRpsDelta, 2)
            paired_rps_delta_sd_pp = [math]::Round($rpsDeltaSd, 2)
            baseline_median_p99_ms = [math]::Round($baselineP99Median, 2)
            candidate_median_p99_ms = [math]::Round($candidateP99Median, 2)
            p99_delta_pct = [math]::Round($decisionP99Delta, 2)
            median_paired_p99_delta_pct = [math]::Round($medianP99Delta, 2)
            paired_p99_delta_sd_pp = [math]::Round($p99DeltaSd, 2)
            median_503_delta_pp = [math]::Round($median503Delta, 2)
            median_memory_delta_mib = [math]::Round($medianMemoryDelta, 2)
            gate = if ($inconclusive.Count -gt 0) {
                "INCONCLUSIVE: $($inconclusive -join ',')"
            } elseif ($failures.Count -gt 0) {
                "FAIL: $($failures -join ',')"
            } else {
                "PASS"
            }
        }
    } | Sort-Object endpoint_class, concurrency)

New-Item -ItemType Directory -Force $OutputDir | Out-Null
$pairs | Export-Csv -LiteralPath (Join-Path $OutputDir "paired_runs.csv") -NoTypeInformation -Encoding utf8
$comparison | Export-Csv -LiteralPath (Join-Path $OutputDir "crossover_comparison.csv") -NoTypeInformation -Encoding utf8
$blocked = @($comparison | Where-Object Gate -ne "PASS")
$gate = if ($blocked.Count -eq 0) { "PASS" } else { "BLOCKED" }

$report = [System.Collections.Generic.List[string]]::new()
$report.Add("# Resident Crossover Comparison")
$report.Add("")
$report.Add("- Gate result: $gate")
$report.Add("- Effect statistic: crossover-pooled candidate/baseline medians")
$report.Add("- Stability statistic: same-phase paired delta standard deviation")
$report.Add("- Minimum pairs: $MinRuns")
$report.Add("- Useful RPS median delta: >= $MinUsefulRpsDeltaPercent%")
$report.Add("- p99 median delta: <= $MaxP99RegressionPercent%")
$report.Add("- Pair-delta stability: RPS SD <= $MaxRpsPairDeltaStandardDeviation pp; p99 SD <= $MaxP99PairDeltaStandardDeviation pp")
$report.Add("")
$report.Add("| Class | C | Pairs | Useful RPS B/C | Effect delta | Pair delta SD | p99 B/C ms | Effect delta | Pair delta SD | 503 delta | Memory delta MiB | Gate |")
$report.Add("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|")
foreach ($row in $comparison) {
    $report.Add("| $($row.endpoint_class) | $($row.concurrency) | $($row.pairs) | $($row.baseline_median_useful_200_rps)/$($row.candidate_median_useful_200_rps) | $($row.useful_200_rps_delta_pct)% | $($row.paired_rps_delta_sd_pp) pp | $($row.baseline_median_p99_ms)/$($row.candidate_median_p99_ms) | $($row.p99_delta_pct)% | $($row.paired_p99_delta_sd_pp) pp | $($row.median_503_delta_pp) pp | $($row.median_memory_delta_mib) | $($row.gate) |")
}
$reportPath = Join-Path $OutputDir "comparison.md"
$report | Set-Content -LiteralPath $reportPath -Encoding utf8
Write-Output "Crossover comparison report: $reportPath"
Write-Output "Gate result: $gate"

if ($FailOnGate -and $gate -ne "PASS") {
    exit 2
}
