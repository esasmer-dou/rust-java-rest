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
    [string] $FrameworkMemory = "128m",
    [int] $RandomSeed = 20260715,
    [string] $ResultsDir = "",
    [switch] $PlanPreWarm,
    [switch] $FailOnGate
)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false

if ($PairRepeats -lt 1) {
    throw "PairRepeats must be >= 1. Each repeat executes baseline/candidate/candidate/baseline."
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Runner = Join-Path $ScriptDir "container_benchmark.ps1"
$Comparer = Join-Path $ScriptDir "compare_framework_results.ps1"
$BenchmarkTag = "rust-java-rest:benchmark"

if ([string]::IsNullOrWhiteSpace($ResultsDir)) {
    $ResultsDir = Join-Path $ScriptDir ("results\paired_image_gate_{0}" -f (Get-Date -Format "yyyyMMdd_HHmmss"))
}
$ResultsDir = [System.IO.Path]::GetFullPath($ResultsDir)
$BaselineAggregateDir = Join-Path $ResultsDir "baseline"
$CandidateAggregateDir = Join-Path $ResultsDir "candidate"
$ComparisonDir = Join-Path $ResultsDir "comparison"
New-Item -ItemType Directory -Force -Path $BaselineAggregateDir, $CandidateAggregateDir | Out-Null

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
    $arguments = @{
        ConcurrencyLevels = $ConcurrencyLevels
        Duration = $Duration
        Warmup = $Warmup
        Threads = $Threads
        CpuLimit = $CpuLimit
        RuntimeProfile = $RuntimeProfile
        FrameworkJavaToolOptions = $FrameworkJavaToolOptions
        FrameworkMemory = $FrameworkMemory
        ResultsDir = $runDir
        RepeatCount = 1
        RandomizeOrder = $true
        RandomSeed = $RandomSeed + $Cycle
        EndpointClasses = $EndpointClasses
        FrameworkOnly = $true
        SkipBuild = $true
        SkipImageBuild = $true
    }
    if ($PlanPreWarm) {
        $arguments.PlanPreWarm = $true
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
if ($baselineId -eq $candidateId) {
    throw "Baseline and candidate resolve to the same image id: $baselineId"
}

$previousBenchmarkId = $null
try {
    $previousBenchmarkId = Get-ImageId $BenchmarkTag
} catch {
    $previousBenchmarkId = $null
}

$baselineRows = [System.Collections.Generic.List[object]]::new()
$candidateRows = [System.Collections.Generic.List[object]]::new()
$sequence = @(
    [PSCustomObject]@{ Variant = "baseline"; Image = $BaselineImage },
    [PSCustomObject]@{ Variant = "candidate"; Image = $CandidateImage },
    [PSCustomObject]@{ Variant = "candidate"; Image = $CandidateImage },
    [PSCustomObject]@{ Variant = "baseline"; Image = $BaselineImage }
)

try {
    for ($cycle = 1; $cycle -le $PairRepeats; $cycle++) {
        for ($position = 1; $position -le $sequence.Count; $position++) {
            $item = $sequence[$position - 1]
            $result = Invoke-ImageRun -Variant $item.Variant -Image $item.Image -Cycle $cycle -Position $position
            $rows = @($result)
            if ($item.Variant -eq "baseline") {
                $rows | ForEach-Object { $baselineRows.Add($_) }
            } else {
                $rows | ForEach-Object { $candidateRows.Add($_) }
            }
        }
    }
} finally {
    if (-not [string]::IsNullOrWhiteSpace($previousBenchmarkId)) {
        Invoke-DockerCapture @("tag", $previousBenchmarkId, $BenchmarkTag) | Out-Null
    }
}

$baselineRows | Export-Csv -LiteralPath (Join-Path $BaselineAggregateDir "results.csv") -NoTypeInformation -Encoding utf8
$candidateRows | Export-Csv -LiteralPath (Join-Path $CandidateAggregateDir "results.csv") -NoTypeInformation -Encoding utf8

$metadata = [ordered]@{
    baseline_image = $BaselineImage
    baseline_image_id = $baselineId
    candidate_image = $CandidateImage
    candidate_image_id = $candidateId
    sequence = "baseline,candidate,candidate,baseline"
    pair_repeats = $PairRepeats
    endpoint_classes = $EndpointClasses
    concurrency_levels = "$ConcurrencyLevels"
    duration = $Duration
    warmup = $Warmup
    runtime_profile = $RuntimeProfile
    framework_java_tool_options = $FrameworkJavaToolOptions
    cpu_limit = $CpuLimit
    memory_limit = $FrameworkMemory
}
$metadata | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $ResultsDir "metadata.json") -Encoding utf8

$compareArgs = @{
    BaselineResultsDir = $BaselineAggregateDir
    CandidateResultsDir = $CandidateAggregateDir
    OutputDir = $ComparisonDir
}
if ($FailOnGate) {
    $compareArgs.FailOnGate = $true
}
& $Comparer @compareArgs
exit $LASTEXITCODE
