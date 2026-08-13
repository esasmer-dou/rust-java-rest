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
    [double] $MaxProcessRssRegressionMiB = 1.0,
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

function Get-PhaseLogMedian {
    param(
        [object[]] $Rows,
        [string] $Phase,
        [string] $BaselineProperty,
        [string] $CandidateProperty
    )
    $logRatios = @($Rows | Where-Object Phase -eq $Phase | ForEach-Object {
        $baseline = [double] $_.$BaselineProperty
        $candidate = [double] $_.$CandidateProperty
        if ($baseline -le 0 -or $candidate -le 0) {
            throw "Crossover ratio values must be positive: $Phase/$BaselineProperty/$CandidateProperty"
        }
        [math]::Log($candidate / $baseline)
    })
    if ($logRatios.Count -eq 0) {
        throw "Crossover phase $Phase is missing."
    }
    return Get-Median $logRatios
}

function Get-CrossoverPercentEffect {
    param(
        [object[]] $Rows,
        [string] $BaselineProperty,
        [string] $CandidateProperty
    )
    $ab = Get-PhaseLogMedian $Rows "AB" $BaselineProperty $CandidateProperty
    $ba = Get-PhaseLogMedian $Rows "BA" $BaselineProperty $CandidateProperty
    return 100.0 * ([math]::Exp(($ab + $ba) / 2.0) - 1.0)
}

function Get-PhasePercentEffect {
    param(
        [object[]] $Rows,
        [string] $Phase,
        [string] $BaselineProperty,
        [string] $CandidateProperty
    )
    $phaseLogMedian = Get-PhaseLogMedian $Rows $Phase $BaselineProperty $CandidateProperty
    return 100.0 * ([math]::Exp($phaseLogMedian) - 1.0)
}

function Get-WithinPhaseRatioVariationPercent {
    param(
        [object[]] $Rows,
        [string] $BaselineProperty,
        [string] $CandidateProperty
    )
    $residuals = [System.Collections.Generic.List[double]]::new()
    foreach ($phase in "AB", "BA") {
        $phaseRows = @($Rows | Where-Object Phase -eq $phase)
        $center = Get-PhaseLogMedian $Rows $phase $BaselineProperty $CandidateProperty
        foreach ($row in $phaseRows) {
            $baseline = [double] $row.$BaselineProperty
            $candidate = [double] $row.$CandidateProperty
            $residuals.Add([math]::Log($candidate / $baseline) - $center)
        }
    }
    $logSd = Get-StandardDeviation $residuals
    return 100.0 * ([math]::Exp($logSd) - 1.0)
}

function Get-CrossoverAdditiveEffect {
    param([object[]] $Rows, [string] $DeltaProperty)
    $ab = Get-Median @($Rows | Where-Object Phase -eq "AB" | ForEach-Object $DeltaProperty)
    $ba = Get-Median @($Rows | Where-Object Phase -eq "BA" | ForEach-Object $DeltaProperty)
    return ($ab + $ba) / 2.0
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
        $baselineProcessRss = Convert-ToDoubleValue $baselineRow.RssAfterMiB
        $candidateProcessRss = Convert-ToDoubleValue $candidateRow.RssAfterMiB
        $baselineContainerMemory = Convert-ToDoubleValue $baselineRow.MaxContainerMemMiB
        $candidateContainerMemory = Convert-ToDoubleValue $candidateRow.MaxContainerMemMiB
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
            BaselineProcessRssMiB = $baselineProcessRss
            CandidateProcessRssMiB = $candidateProcessRss
            ProcessRssDeltaMiB = $candidateProcessRss - $baselineProcessRss
            BaselineContainerMemoryMiB = $baselineContainerMemory
            CandidateContainerMemoryMiB = $candidateContainerMemory
            ContainerMemoryDeltaMiB = $candidateContainerMemory - $baselineContainerMemory
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
        $failures = [System.Collections.Generic.List[string]]::new()
        $inconclusive = [System.Collections.Generic.List[string]]::new()
        $baselineUsefulMedian = Get-Median @($rows | ForEach-Object BaselineUsefulRps)
        $candidateUsefulMedian = Get-Median @($rows | ForEach-Object CandidateUsefulRps)
        $baselineP99Median = Get-Median @($rows | ForEach-Object BaselineP99Ms)
        $candidateP99Median = Get-Median @($rows | ForEach-Object CandidateP99Ms)
        $baselineProcessRssMedian = Get-Median @($rows | ForEach-Object BaselineProcessRssMiB)
        $candidateProcessRssMedian = Get-Median @($rows | ForEach-Object CandidateProcessRssMiB)
        $baselineContainerMemoryMedian = Get-Median @($rows | ForEach-Object BaselineContainerMemoryMiB)
        $candidateContainerMemoryMedian = Get-Median @($rows | ForEach-Object CandidateContainerMemoryMiB)
        # In phase AB the candidate occupies slot B; in BA it occupies slot A. The geometric
        # mean of phase ratios cancels multiplicative CPU-slot capacity. Stability is measured
        # after removing each phase center, otherwise an asymmetric host is misreported as an
        # unstable candidate.
        $decisionRpsDelta = Get-CrossoverPercentEffect $rows `
                "BaselineUsefulRps" "CandidateUsefulRps"
        $decisionP99Delta = Get-CrossoverPercentEffect $rows `
                "BaselineP99Ms" "CandidateP99Ms"
        $phaseAbRpsDelta = Get-PhasePercentEffect $rows "AB" `
                "BaselineUsefulRps" "CandidateUsefulRps"
        $phaseBaRpsDelta = Get-PhasePercentEffect $rows "BA" `
                "BaselineUsefulRps" "CandidateUsefulRps"
        $phaseAbP99Delta = Get-PhasePercentEffect $rows "AB" `
                "BaselineP99Ms" "CandidateP99Ms"
        $phaseBaP99Delta = Get-PhasePercentEffect $rows "BA" `
                "BaselineP99Ms" "CandidateP99Ms"
        $rpsDeltaSd = Get-WithinPhaseRatioVariationPercent $rows `
                "BaselineUsefulRps" "CandidateUsefulRps"
        $p99DeltaSd = Get-WithinPhaseRatioVariationPercent $rows `
                "BaselineP99Ms" "CandidateP99Ms"
        $decision503Delta = Get-CrossoverAdditiveEffect $rows "Status503DeltaPp"
        $decisionProcessRssDelta = Get-CrossoverAdditiveEffect $rows "ProcessRssDeltaMiB"
        $decisionContainerMemoryDelta = Get-CrossoverAdditiveEffect $rows "ContainerMemoryDeltaMiB"

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
        if ($decision503Delta -gt $Max503DeltaPercentagePoints) {
            $failures.Add("503-rate")
        }
        if ($decisionProcessRssDelta -gt $MaxProcessRssRegressionMiB) {
            $failures.Add("process-rss")
        }
        if ($decisionContainerMemoryDelta -gt $MaxMemoryRegressionMiB) {
            $failures.Add("container-memory")
        }

        [PSCustomObject]@{
            endpoint_class = $first.EndpointClass
            endpoint = $first.Endpoint
            concurrency = $first.Concurrency
            pairs = $rows.Count
            baseline_median_useful_200_rps = [math]::Round($baselineUsefulMedian, 2)
            candidate_median_useful_200_rps = [math]::Round($candidateUsefulMedian, 2)
            useful_200_rps_delta_pct = [math]::Round($decisionRpsDelta, 2)
            phase_ab_rps_delta_pct = [math]::Round($phaseAbRpsDelta, 2)
            phase_ba_rps_delta_pct = [math]::Round($phaseBaRpsDelta, 2)
            within_phase_rps_variation_pct = [math]::Round($rpsDeltaSd, 2)
            baseline_median_p99_ms = [math]::Round($baselineP99Median, 2)
            candidate_median_p99_ms = [math]::Round($candidateP99Median, 2)
            p99_delta_pct = [math]::Round($decisionP99Delta, 2)
            phase_ab_p99_delta_pct = [math]::Round($phaseAbP99Delta, 2)
            phase_ba_p99_delta_pct = [math]::Round($phaseBaP99Delta, 2)
            within_phase_p99_variation_pct = [math]::Round($p99DeltaSd, 2)
            crossover_503_delta_pp = [math]::Round($decision503Delta, 2)
            baseline_median_process_rss_mib = [math]::Round($baselineProcessRssMedian, 2)
            candidate_median_process_rss_mib = [math]::Round($candidateProcessRssMedian, 2)
            process_rss_delta_mib = [math]::Round($decisionProcessRssDelta, 2)
            baseline_median_container_memory_mib = [math]::Round($baselineContainerMemoryMedian, 2)
            candidate_median_container_memory_mib = [math]::Round($candidateContainerMemoryMedian, 2)
            container_memory_delta_mib = [math]::Round($decisionContainerMemoryDelta, 2)
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
$report.Add("- Effect statistic: balanced AB/BA crossover estimate; CPU-slot capacity is cancelled")
$report.Add("- Stability statistic: within-phase log-ratio residual variation")
$report.Add("- Minimum pairs: $MinRuns")
$report.Add("- Useful RPS median delta: >= $MinUsefulRpsDeltaPercent%")
$report.Add("- p99 median delta: <= $MaxP99RegressionPercent%")
$report.Add("- Process RSS delta: <= +$MaxProcessRssRegressionMiB MiB")
$report.Add("- Container memory delta: <= +$MaxMemoryRegressionMiB MiB")
$report.Add("- Within-phase stability: RPS <= $MaxRpsPairDeltaStandardDeviation%; p99 <= $MaxP99PairDeltaStandardDeviation%")
$report.Add("")
$report.Add("| Class | C | Pairs | Useful RPS B/C | Crossover delta | AB/BA delta | Within-phase variation | p99 B/C ms | Crossover delta | AB/BA delta | Within-phase variation | 503 delta | Process RSS B/C MiB | RSS delta | Container delta | Gate |")
$report.Add("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|")
foreach ($row in $comparison) {
    $report.Add("| $($row.endpoint_class) | $($row.concurrency) | $($row.pairs) | $($row.baseline_median_useful_200_rps)/$($row.candidate_median_useful_200_rps) | $($row.useful_200_rps_delta_pct)% | $($row.phase_ab_rps_delta_pct)%/$($row.phase_ba_rps_delta_pct)% | $($row.within_phase_rps_variation_pct)% | $($row.baseline_median_p99_ms)/$($row.candidate_median_p99_ms) | $($row.p99_delta_pct)% | $($row.phase_ab_p99_delta_pct)%/$($row.phase_ba_p99_delta_pct)% | $($row.within_phase_p99_variation_pct)% | $($row.crossover_503_delta_pp) pp | $($row.baseline_median_process_rss_mib)/$($row.candidate_median_process_rss_mib) | $($row.process_rss_delta_mib) | $($row.container_memory_delta_mib) | $($row.gate) |")
}
$summary = [ordered]@{
    gate = $gate
    comparisons = $comparison
    thresholds = [ordered]@{
        min_useful_rps_delta_percent = $MinUsefulRpsDeltaPercent
        max_p99_regression_percent = $MaxP99RegressionPercent
        max_503_delta_percentage_points = $Max503DeltaPercentagePoints
        max_process_rss_regression_mib = $MaxProcessRssRegressionMiB
        max_container_memory_regression_mib = $MaxMemoryRegressionMiB
        max_rps_pair_delta_sd_pp = $MaxRpsPairDeltaStandardDeviation
        max_p99_pair_delta_sd_pp = $MaxP99PairDeltaStandardDeviation
    }
}
$summary | ConvertTo-Json -Depth 6 |
        Set-Content -LiteralPath (Join-Path $OutputDir "gate-summary.json") -Encoding utf8
$reportPath = Join-Path $OutputDir "comparison.md"
$report | Set-Content -LiteralPath $reportPath -Encoding utf8
Write-Output "Crossover comparison report: $reportPath"
Write-Output "Gate result: $gate"

if ($FailOnGate -and $gate -ne "PASS") {
    exit 2
}
