param(
    [Parameter(Mandatory = $true)]
    [string] $BaselineImage,

    [Parameter(Mandatory = $true)]
    [string] $CandidateImage,

    [object] $ConcurrencyLevels = @(64, 256),
    [string] $EndpointClasses = "small-json-direct,direct-json-writer,dynamic-producer-json,raw-json",
    [string] $Duration = "10s",
    [string] $Warmup = "3s",
    [int] $PairRepeats = 2,
    [int] $Threads = 4,
    [double] $CpuLimit = 1.0,
    [string] $RuntimeProfile = "micro-rest",
    [string] $FrameworkJavaToolOptions = "",
    [string] $FrameworkJavaOptsAppend = "",
    [string] $BaselineJavaOptsAppend = "",
    [string] $CandidateJavaOptsAppend = "",
    [string] $FrameworkMemory = "128m",
    [int] $RandomSeed = 20260715,
    [string] $ResultsDir = "",
    [switch] $PlanPreWarm,
    [string] $PlanPreWarmDuration = "3s",
    [ValidateRange(0, 1)]
    [int] $CalibrationCycles = 1,
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
$PSNativeCommandUseErrorActionPreference = $false

if ($PairRepeats -lt 1) {
    throw "PairRepeats must be >= 1. Cycles alternate the baseline/candidate outer positions."
}
if (($PairRepeats % 2) -ne 0) {
    $message = "Odd PairRepeats leaves outer/middle position exposure unbalanced. " +
        "Use an even value; release evidence should use PairRepeats >= 4."
    if ($FailOnGate) {
        throw $message
    }
    Write-Warning $message
}
if ($FailOnGate -and $PairRepeats -lt 4) {
    throw "Release evidence requires PairRepeats >= 4 for balanced position exposure."
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Runner = Join-Path $ScriptDir "container_benchmark.ps1"
$Comparer = Join-Path $ScriptDir "compare_framework_results.ps1"
$BenchmarkTag = "rust-java-rest:benchmark"
$RunnerImage = "reactor-benchmark-runner:local"

$strictConcurrencyLevels = @(
    if ($ConcurrencyLevels -is [string]) {
        $ConcurrencyLevels.Split(',') |
                ForEach-Object { $_.Trim() } |
                Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
                ForEach-Object { [int]$_ }
    } else {
        @($ConcurrencyLevels) | ForEach-Object { [int]$_ }
    }
)
if ($strictConcurrencyLevels.Count -eq 0) {
    throw "At least one concurrency level is required."
}

if ([string]::IsNullOrWhiteSpace($ResultsDir)) {
    $ResultsDir = Join-Path $ScriptDir ("results\paired_image_gate_{0}" -f (Get-Date -Format "yyyyMMdd_HHmmss"))
}
$ResultsDir = [System.IO.Path]::GetFullPath($ResultsDir)
$BaselineAggregateDir = Join-Path $ResultsDir "baseline"
$CandidateAggregateDir = Join-Path $ResultsDir "candidate"
$ComparisonDir = Join-Path $ResultsDir "comparison"
New-Item -ItemType Directory -Force -Path $BaselineAggregateDir, $CandidateAggregateDir | Out-Null

& docker build -t $RunnerImage -f (Join-Path $ScriptDir "Dockerfile.benchmark") $ScriptDir | Out-Host
if ($LASTEXITCODE -ne 0) {
    throw "Benchmark runner image build failed."
}

function Invoke-DockerCapture {
    param([string[]] $Arguments)

    $output = & docker @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "docker $($Arguments -join ' ') failed:`n$($output -join [Environment]::NewLine)"
    }
    return (($output | Select-Object -First 1) -as [string]).Trim()
}

function Get-ImageId {
    param([string] $Image)
    return Invoke-DockerCapture @("image", "inspect", "--format", "{{.Id}}", $Image)
}

function Invoke-ImageRun {
    param(
        [string] $Variant,
        [string] $Image,
        [int] $Cycle,
        [int] $Position
    )

    Invoke-DockerCapture @("tag", $Image, $BenchmarkTag) | Out-Null
    $runDir = Join-Path $ResultsDir ("runs\cycle-{0:D2}-{1:D2}-{2}" -f $Cycle, $Position, $Variant)
    $variantJavaOptsAppend = if ($Variant -eq "baseline") {
        $BaselineJavaOptsAppend
    } else {
        $CandidateJavaOptsAppend
    }
    $effectiveJavaOptsAppend = @(
        $FrameworkJavaOptsAppend,
        $variantJavaOptsAppend
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

    $arguments = @{
        ConcurrencyLevels = $ConcurrencyLevels
        Duration = $Duration
        Warmup = $Warmup
        Threads = $Threads
        CpuLimit = $CpuLimit
        RuntimeProfile = $RuntimeProfile
        FrameworkJavaToolOptions = $FrameworkJavaToolOptions
        FrameworkJavaOptsAppend = ($effectiveJavaOptsAppend -join " ")
        FrameworkMemory = $FrameworkMemory
        ResultsDir = $runDir
        RepeatCount = 1
        RandomizeOrder = $true
        RandomSeed = $RandomSeed + $Cycle
        EndpointClasses = $EndpointClasses
        FrameworkOnly = $true
        SkipBuild = $true
        SkipImageBuild = $true
        SkipRunnerImageBuild = $true
    }
    if ($PlanPreWarm) {
        $arguments.PlanPreWarm = $true
        $arguments.PlanPreWarmDuration = $PlanPreWarmDuration
    }
    & $Runner @arguments | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "$Variant benchmark failed in cycle $Cycle position $Position."
    }

    $resultsPath = Join-Path $runDir "results.csv"
    if (-not (Test-Path -LiteralPath $resultsPath)) {
        throw "Missing benchmark result: $resultsPath"
    }
    return @(Import-Csv -LiteralPath $resultsPath | ForEach-Object {
        $_ | Add-Member -NotePropertyName PairCycle -NotePropertyValue $Cycle
        $_ | Add-Member -NotePropertyName PairPosition -NotePropertyValue $Position
        $_ | Add-Member -NotePropertyName ImageId -NotePropertyValue (Get-ImageId $Image)
        $_
    })
}

$baselineId = Get-ImageId $BaselineImage
$candidateId = Get-ImageId $CandidateImage
if ($baselineId -eq $candidateId `
        -and $BaselineJavaOptsAppend.Trim() -eq $CandidateJavaOptsAppend.Trim()) {
    throw "Baseline and candidate resolve to the same image id with identical JVM options: $baselineId"
}

$previousBenchmarkId = $null
try {
    $previousBenchmarkId = Get-ImageId $BenchmarkTag
} catch {
    $previousBenchmarkId = $null
}

$baselineRows = [System.Collections.Generic.List[object]]::new()
$candidateRows = [System.Collections.Generic.List[object]]::new()

function Save-AggregateCheckpoint {
    param([int] $CompletedCycle, [int] $CompletedPosition)

    if ($baselineRows.Count -gt 0) {
        $baselineRows | Export-Csv -LiteralPath (Join-Path $BaselineAggregateDir "results.csv") `
                -NoTypeInformation -Encoding utf8
    }
    if ($candidateRows.Count -gt 0) {
        $candidateRows | Export-Csv -LiteralPath (Join-Path $CandidateAggregateDir "results.csv") `
                -NoTypeInformation -Encoding utf8
    }
    [ordered]@{
        completed_cycle = $CompletedCycle
        completed_position = $CompletedPosition
        baseline_rows = $baselineRows.Count
        candidate_rows = $candidateRows.Count
        complete = $false
        updated_at = (Get-Date).ToString("o")
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $ResultsDir "progress.json") -Encoding utf8
}

$forwardSequence = @(
    [PSCustomObject]@{ Variant = "baseline"; Image = $BaselineImage },
    [PSCustomObject]@{ Variant = "candidate"; Image = $CandidateImage },
    [PSCustomObject]@{ Variant = "candidate"; Image = $CandidateImage },
    [PSCustomObject]@{ Variant = "baseline"; Image = $BaselineImage }
)
$reverseSequence = @(
    [PSCustomObject]@{ Variant = "candidate"; Image = $CandidateImage },
    [PSCustomObject]@{ Variant = "baseline"; Image = $BaselineImage },
    [PSCustomObject]@{ Variant = "baseline"; Image = $BaselineImage },
    [PSCustomObject]@{ Variant = "candidate"; Image = $CandidateImage }
)

try {
    if ($CalibrationCycles -eq 1) {
        Write-Host "Running unrecorded baseline/candidate calibration cycle."
        $calibrationSequence = @(
            [PSCustomObject]@{ Variant = "baseline"; Image = $BaselineImage },
            [PSCustomObject]@{ Variant = "candidate"; Image = $CandidateImage }
        )
        for ($position = 1; $position -le $calibrationSequence.Count; $position++) {
            $item = $calibrationSequence[$position - 1]
            Invoke-ImageRun `
                    -Variant $item.Variant `
                    -Image $item.Image `
                    -Cycle 0 `
                    -Position $position | Out-Null
        }
    }
    for ($cycle = 1; $cycle -le $PairRepeats; $cycle++) {
        # Alternate the outer positions so page-cache, thermal and scheduler drift do not
        # consistently favour one image across repeated cycles.
        $sequence = if (($cycle % 2) -eq 1) { $forwardSequence } else { $reverseSequence }
        for ($position = 1; $position -le $sequence.Count; $position++) {
            $item = $sequence[$position - 1]
            $result = Invoke-ImageRun -Variant $item.Variant -Image $item.Image -Cycle $cycle -Position $position
            $rows = @($result)
            if ($item.Variant -eq "baseline") {
                $rows | ForEach-Object { $baselineRows.Add($_) }
            } else {
                $rows | ForEach-Object { $candidateRows.Add($_) }
            }
            Save-AggregateCheckpoint -CompletedCycle $cycle -CompletedPosition $position
        }
    }
} finally {
    if (-not [string]::IsNullOrWhiteSpace($previousBenchmarkId)) {
        Invoke-DockerCapture @("tag", $previousBenchmarkId, $BenchmarkTag) | Out-Null
    }
}

Save-AggregateCheckpoint -CompletedCycle $PairRepeats -CompletedPosition 4
[ordered]@{
    completed_cycle = $PairRepeats
    completed_position = 4
    baseline_rows = $baselineRows.Count
    candidate_rows = $candidateRows.Count
    complete = $true
    updated_at = (Get-Date).ToString("o")
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $ResultsDir "progress.json") -Encoding utf8

$metadata = [ordered]@{
    baseline_image = $BaselineImage
    baseline_image_id = $baselineId
    candidate_image = $CandidateImage
    candidate_image_id = $candidateId
    sequence_policy = "odd=baseline,candidate,candidate,baseline;even=candidate,baseline,baseline,candidate"
    pair_repeats = $PairRepeats
    calibration_cycles = $CalibrationCycles
    endpoint_classes = $EndpointClasses
    concurrency_levels = "$ConcurrencyLevels"
    duration = $Duration
    warmup = $Warmup
    plan_pre_warm = $PlanPreWarm.IsPresent
    plan_pre_warm_duration = if ($PlanPreWarm) { $PlanPreWarmDuration } else { "disabled" }
    runtime_profile = $RuntimeProfile
    framework_java_tool_options = $FrameworkJavaToolOptions
    framework_java_opts_append = $FrameworkJavaOptsAppend
    baseline_java_opts_append = $BaselineJavaOptsAppend
    candidate_java_opts_append = $CandidateJavaOptsAppend
    cpu_limit = $CpuLimit
    memory_limit = $FrameworkMemory
    strict_concurrency_levels = $strictConcurrencyLevels
    min_useful_rps_delta_percent = $MinUsefulRpsDeltaPercent
    max_p99_regression_percent = $MaxP99RegressionPercent
    max_503_delta_percentage_points = $Max503DeltaPercentagePoints
    max_memory_regression_mib = $MaxMemoryRegressionMiB
    min_strict_runs = $MinStrictRuns
    max_useful_rps_cv_percent = $MaxUsefulRpsCoefficientVariationPercent
    max_p99_cv_percent = $MaxP99CoefficientVariationPercent
    max_startup_regression_percent = $MaxStartupRegressionPercent
    max_startup_cv_percent = $MaxStartupCoefficientVariationPercent
}
$metadata | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $ResultsDir "metadata.json") -Encoding utf8

$compareArgs = @{
    BaselineResultsDir = $BaselineAggregateDir
    CandidateResultsDir = $CandidateAggregateDir
    OutputDir = $ComparisonDir
    StrictConcurrencyLevels = $strictConcurrencyLevels
    MinUsefulRpsDeltaPercent = $MinUsefulRpsDeltaPercent
    MaxP99RegressionPercent = $MaxP99RegressionPercent
    Max503DeltaPercentagePoints = $Max503DeltaPercentagePoints
    MaxMemoryRegressionMiB = $MaxMemoryRegressionMiB
    MinStrictRuns = $MinStrictRuns
    MaxUsefulRpsCoefficientVariationPercent = $MaxUsefulRpsCoefficientVariationPercent
    MaxP99CoefficientVariationPercent = $MaxP99CoefficientVariationPercent
    MaxStartupRegressionPercent = $MaxStartupRegressionPercent
    MaxStartupCoefficientVariationPercent = $MaxStartupCoefficientVariationPercent
}
if ($FailOnGate) {
    $compareArgs.FailOnGate = $true
}
& $Comparer @compareArgs
exit $LASTEXITCODE
