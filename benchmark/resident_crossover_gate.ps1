param(
    [Parameter(Mandatory = $true)]
    [string] $BaselineImage,

    [Parameter(Mandatory = $true)]
    [string] $CandidateImage,

    [string] $ResultsDir = "",
    [int] $RepeatCountPerSlot = 3,
    [int] $Concurrency = 256,
    [string] $Duration = "15s",
    [string] $PreWarmDuration = "10s",
    [string] $EndpointClasses = "annotated-generated-json,echo-parse,small-json-direct",
    [int] $InterPairCooldownSeconds = 5,
    [int] $PhaseCooldownSeconds = 60,
    [double] $CpuLimit = 1.0,
    [string] $MemoryLimit = "128m",
    [string] $SlotACpuSet = "2",
    [string] $SlotBCpuSet = "3",
    [string] $RunnerCpuSet = "4-7",
    [double] $RunnerCpuLimit = 2.0,
    [string] $RunnerImage = "reactor-benchmark-runner:local",
    [string] $BaselineJavaOptsAppend = "",
    [string] $CandidateJavaOptsAppend = "",
    [string] $AdditionalNetwork = "",
    [double] $MinUsefulRpsDeltaPercent = -2.0,
    [double] $MaxP99RegressionPercent = 10.0,
    [double] $Max503DeltaPercentagePoints = 2.0,
    [double] $MaxProcessRssRegressionMiB = 1.0,
    [double] $MaxContainerMemoryRegressionMiB = 1.0,
    [double] $MaxRpsPairDeltaStandardDeviation = 10.0,
    [double] $MaxP99PairDeltaStandardDeviation = 15.0,
    [switch] $FailOnGate
)

$ErrorActionPreference = "Stop"

if ($RepeatCountPerSlot -lt 3) {
    throw "RepeatCountPerSlot must be at least 3."
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($ResultsDir)) {
    $ResultsDir = Join-Path $ScriptDir ("results\resident_crossover_" + (Get-Date -Format "yyyyMMdd_HHmmss"))
}
$ResultsDir = [System.IO.Path]::GetFullPath($ResultsDir)
$phaseAb = Join-Path $ResultsDir "phase-ab"
$phaseBa = Join-Path $ResultsDir "phase-ba"
$baselineDir = Join-Path $ResultsDir "baseline"
$candidateDir = Join-Path $ResultsDir "candidate"
$comparisonDir = Join-Path $ResultsDir "comparison"
$absoluteComparisonDir = Join-Path $comparisonDir "absolute"
New-Item -ItemType Directory -Force $baselineDir, $candidateDir, $comparisonDir | Out-Null

function Invoke-Phase {
    param(
        [string] $Baseline,
        [string] $Candidate,
        [string] $BaselineOptions,
        [string] $CandidateOptions,
        [string] $Output
    )

    & (Join-Path $ScriptDir "resident_image_gate.ps1") `
            -BaselineImage $Baseline `
            -CandidateImage $Candidate `
            -ResultsDir $Output `
            -RepeatCount $RepeatCountPerSlot `
            -Concurrency $Concurrency `
            -Duration $Duration `
            -PreWarmDuration $PreWarmDuration `
            -EndpointClasses $EndpointClasses `
            -InterPairCooldownSeconds $InterPairCooldownSeconds `
            -CpuLimit $CpuLimit `
            -MemoryLimit $MemoryLimit `
            -BaselineCpuSet $SlotACpuSet `
            -CandidateCpuSet $SlotBCpuSet `
            -RunnerCpuSet $RunnerCpuSet `
            -RunnerCpuLimit $RunnerCpuLimit `
            -RunnerImage $RunnerImage `
            -BaselineJavaOptsAppend $BaselineOptions `
            -CandidateJavaOptsAppend $CandidateOptions `
            -AdditionalNetwork $AdditionalNetwork `
            -MaxMemoryRegressionMiB $MaxContainerMemoryRegressionMiB
    if ($LASTEXITCODE -ne 0) {
        throw "Resident slot phase failed: $Output"
    }
}

function Export-CombinedRows {
    param([object[]] $Rows, [string] $Path)

    $runs = @{}
    foreach ($row in $Rows) {
        $key = "$($row.EndpointClass)|$($row.Concurrency)"
        if (-not $runs.ContainsKey($key)) {
            $runs[$key] = 0
        }
        $runs[$key]++
        $row.Run = $runs[$key]
    }
    $Rows | Export-Csv -LiteralPath $Path -NoTypeInformation -Encoding utf8
}

Invoke-Phase -Baseline $BaselineImage -Candidate $CandidateImage `
        -BaselineOptions $BaselineJavaOptsAppend -CandidateOptions $CandidateJavaOptsAppend `
        -Output $phaseAb
if ($PhaseCooldownSeconds -gt 0) {
    Start-Sleep -Seconds $PhaseCooldownSeconds
}
Invoke-Phase -Baseline $CandidateImage -Candidate $BaselineImage `
        -BaselineOptions $CandidateJavaOptsAppend -CandidateOptions $BaselineJavaOptsAppend `
        -Output $phaseBa

$baselineRows = @(
    Import-Csv -LiteralPath (Join-Path $phaseAb "baseline\results.csv")
) + @(
    Import-Csv -LiteralPath (Join-Path $phaseBa "candidate\results.csv")
)
$candidateRows = @(
    Import-Csv -LiteralPath (Join-Path $phaseAb "candidate\results.csv")
) + @(
    Import-Csv -LiteralPath (Join-Path $phaseBa "baseline\results.csv")
)

Export-CombinedRows -Rows $baselineRows -Path (Join-Path $baselineDir "results.csv")
Export-CombinedRows -Rows $candidateRows -Path (Join-Path $candidateDir "results.csv")

$metadata = [ordered]@{
    baseline_image = $BaselineImage
    candidate_image = $CandidateImage
    execution_model = "two resident phases with image-to-CPU-slot crossover"
    repeats_per_slot = $RepeatCountPerSlot
    total_runs_per_image = 2 * $RepeatCountPerSlot
    concurrency = $Concurrency
    duration = $Duration
    pre_warm_duration = $PreWarmDuration
    endpoint_classes = $EndpointClasses
    inter_pair_cooldown_seconds = $InterPairCooldownSeconds
    phase_cooldown_seconds = $PhaseCooldownSeconds
    slot_a_cpu_set = $SlotACpuSet
    slot_b_cpu_set = $SlotBCpuSet
    runner_cpu_set = $RunnerCpuSet
    runner_cpu_limit = $RunnerCpuLimit
    cpu_limit = $CpuLimit
    memory_limit = $MemoryLimit
    baseline_java_opts_append = $BaselineJavaOptsAppend
    candidate_java_opts_append = $CandidateJavaOptsAppend
    additional_network = $AdditionalNetwork
    min_useful_rps_delta_percent = $MinUsefulRpsDeltaPercent
    max_p99_regression_percent = $MaxP99RegressionPercent
    max_503_delta_percentage_points = $Max503DeltaPercentagePoints
    max_process_rss_regression_mib = $MaxProcessRssRegressionMiB
    max_container_memory_regression_mib = $MaxContainerMemoryRegressionMiB
}
$metadata | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $ResultsDir "metadata.json") -Encoding utf8

& (Join-Path $ScriptDir "compare_framework_results.ps1") `
        -BaselineResultsDir $baselineDir `
        -CandidateResultsDir $candidateDir `
        -OutputDir $absoluteComparisonDir `
        -StrictConcurrencyLevels @($Concurrency) `
        -MinStrictRuns (2 * $RepeatCountPerSlot) `
        -MaxMemoryRegressionMiB $MaxContainerMemoryRegressionMiB
if ($LASTEXITCODE -ne 0) {
    throw "Absolute crossover diagnostic comparison failed."
}

& (Join-Path $ScriptDir "compare_crossover_results.ps1") `
        -PhaseAbDir $phaseAb `
        -PhaseBaDir $phaseBa `
        -OutputDir $comparisonDir `
        -MinRuns (2 * $RepeatCountPerSlot) `
        -MinUsefulRpsDeltaPercent $MinUsefulRpsDeltaPercent `
        -MaxP99RegressionPercent $MaxP99RegressionPercent `
        -Max503DeltaPercentagePoints $Max503DeltaPercentagePoints `
        -MaxProcessRssRegressionMiB $MaxProcessRssRegressionMiB `
        -MaxMemoryRegressionMiB $MaxContainerMemoryRegressionMiB `
        -MaxRpsPairDeltaStandardDeviation $MaxRpsPairDeltaStandardDeviation `
        -MaxP99PairDeltaStandardDeviation $MaxP99PairDeltaStandardDeviation `
        -FailOnGate:$FailOnGate
if ($LASTEXITCODE -ne 0) {
    throw "Paired crossover comparison failed."
}

Write-Output "Resident crossover gate complete: $ResultsDir"
