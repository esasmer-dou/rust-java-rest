param(
    [Parameter(Mandatory = $true)]
    [string] $BaselineImage,

    [Parameter(Mandatory = $true)]
    [string] $CandidateImage,

    [string] $ResultsDir = "",
    [int] $RepeatCount = 6,
    [double] $CpuLimit = 1.0,
    [string] $CpuSet = "2",
    [string] $MemoryLimit = "128m",
    [string] $BaselineJavaOptsAppend = "",
    [string] $CandidateJavaOptsAppend = "",
    [string] $Network = "",
    [int] $CooldownSeconds = 3,
    [int] $TimeoutSeconds = 30,
    [double] $MaxRegressionPercent = 10.0,
    [double] $MaxRegressedPairRatePercent = 20.0,
    [double] $MaxCoefficientVariationPercent = 15.0
)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false

if ($RepeatCount -lt 4 -or ($RepeatCount % 2) -ne 0) {
    throw "RepeatCount must be an even number >= 4 so startup position is balanced."
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($ResultsDir)) {
    $ResultsDir = Join-Path $ScriptDir ("results\startup_image_" + (Get-Date -Format "yyyyMMdd_HHmmss"))
}
$ResultsDir = [System.IO.Path]::GetFullPath($ResultsDir)
$baselineDir = Join-Path $ResultsDir "baseline"
$candidateDir = Join-Path $ResultsDir "candidate"
$comparisonDir = Join-Path $ResultsDir "comparison"
New-Item -ItemType Directory -Force $baselineDir, $candidateDir, $comparisonDir | Out-Null

$javaOpts = @(
    "-Xms8m",
    "-Xmx40m",
    "-Xss256k",
    "-Xquickstart",
    "-Xtune:virtualized",
    "-Xshareclasses:none",
    "-XX:ActiveProcessorCount=1",
    "-Xgc:threads=1",
    "-XX:-TransparentHugePage",
    "-Dreactor.runtime.profile=micro-rest",
    "-Dreactor.rust.log.level=error",
    "-Dreactor.rust.java.log.level=warn",
    "-Dfile.encoding=UTF-8",
    "-Djava.security.egd=file:/dev/./urandom"
) -join " "

function Get-FreePort {
    $listener = [System.Net.Sockets.TcpListener]::new(
            [System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try {
        return ([System.Net.IPEndPoint] $listener.LocalEndpoint).Port
    } finally {
        $listener.Stop()
    }
}

function Get-Median {
    param([double[]] $Values)
    $sorted = @($Values | Sort-Object)
    if (($sorted.Count % 2) -eq 1) {
        return $sorted[[int] [math]::Floor($sorted.Count / 2)]
    }
    $upper = [int] ($sorted.Count / 2)
    return ($sorted[$upper - 1] + $sorted[$upper]) / 2.0
}

function Get-CvPercent {
    param([double[]] $Values)
    $average = ($Values | Measure-Object -Average).Average
    if ($Values.Count -lt 2 -or $average -eq 0) {
        return 0.0
    }
    $sum = 0.0
    foreach ($value in $Values) {
        $sum += [math]::Pow($value - $average, 2)
    }
    return 100.0 * [math]::Sqrt($sum / $Values.Count) / [math]::Abs($average)
}

function Get-DeltaPercent {
    param([double] $Baseline, [double] $Candidate)
    if ($Baseline -eq 0) {
        return 0.0
    }
    return 100.0 * ($Candidate - $Baseline) / $Baseline
}

$httpClient = [System.Net.Http.HttpClient]::new()
$httpClient.Timeout = [TimeSpan]::FromMilliseconds(500)
$baselineRows = [System.Collections.Generic.List[object]]::new()
$candidateRows = [System.Collections.Generic.List[object]]::new()
$suffix = [Guid]::NewGuid().ToString("N").Substring(0, 8)

function Measure-Startup {
    param(
        [string] $Variant,
        [string] $Image,
        [int] $Run,
        [int] $Position,
        [switch] $Warmup
    )

    $container = "reactor-startup-$suffix-$Variant-$Run-$Position"
    $port = Get-FreePort
    $variantOptions = if ($Variant -eq "baseline") {
        $BaselineJavaOptsAppend
    } else {
        $CandidateJavaOptsAppend
    }
    $effectiveJavaOpts = @($javaOpts, $variantOptions) |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    $args = @(
        "run", "-d", "--name", $container,
        "--cpus", "$CpuLimit",
        "--memory", $MemoryLimit,
        "-p", "127.0.0.1:${port}:8080",
        "-e", "JAVA_TOOL_OPTIONS=",
        "-e", "JAVA_AGENT_OPTS=",
        "-e", "JAVA_OPTS=$($effectiveJavaOpts -join ' ')"
    )
    if (-not [string]::IsNullOrWhiteSpace($Network)) {
        $args += @("--network", $Network)
    }
    if (-not [string]::IsNullOrWhiteSpace($CpuSet)) {
        $args += @("--cpuset-cpus", $CpuSet)
    }
    $args += $Image

    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $output = & docker @args 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "docker startup failed for ${Variant}:`n$($output -join "`n")"
    }

    $startup = $null
    $reachableMs = -1L
    try {
        while ($stopwatch.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
            try {
                $response = $httpClient.GetAsync(
                        "http://127.0.0.1:${port}/diagnostics/startup").GetAwaiter().GetResult()
                if ($response.IsSuccessStatusCode) {
                    $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                    $candidateStartup = $body | ConvertFrom-Json
                    if ($candidateStartup.ready_ms -ge 0) {
                        $startup = $candidateStartup
                        $reachableMs = [int64] $stopwatch.Elapsed.TotalMilliseconds
                        break
                    }
                }
            } catch {
                Start-Sleep -Milliseconds 50
            }
        }
        if ($null -eq $startup) {
            $logs = & docker logs $container 2>&1
            throw "$Variant did not become HTTP-ready:`n$($logs -join "`n")"
        }

        if (-not $Warmup) {
            return [PSCustomObject]@{
                Variant = $Variant
                Image = $Image
                PairCycle = $Run
                PairPosition = $Position
                StartupReadyMs = [int64] $startup.ready_ms
                StartupReachableMs = $reachableMs
            }
        }
    } finally {
        & docker rm -f $container *> $null
    }
}

try {
    $warmupVariants = @(
        [PSCustomObject]@{ Variant = "baseline"; Image = $BaselineImage },
        [PSCustomObject]@{ Variant = "candidate"; Image = $CandidateImage }
    )
    foreach ($item in $warmupVariants) {
        Measure-Startup -Variant $item.Variant -Image $item.Image `
                -Run 0 -Position 0 -Warmup | Out-Null
    }

    for ($run = 1; $run -le $RepeatCount; $run++) {
        $order = if (($run % 2) -eq 1) {
            @(@("baseline", $BaselineImage), @("candidate", $CandidateImage))
        } else {
            @(@("candidate", $CandidateImage), @("baseline", $BaselineImage))
        }
        for ($position = 0; $position -lt $order.Count; $position++) {
            $variant = $order[$position][0]
            $row = Measure-Startup `
                    -Variant $variant `
                    -Image $order[$position][1] `
                    -Run $run `
                    -Position ($position + 1)
            if ($variant -eq "baseline") {
                $baselineRows.Add($row)
            } else {
                $candidateRows.Add($row)
            }
            if ($CooldownSeconds -gt 0) {
                Start-Sleep -Seconds $CooldownSeconds
            }
        }
    }
} finally {
    $httpClient.Dispose()
    & docker ps -aq --filter "name=reactor-startup-$suffix" |
            ForEach-Object { & docker rm -f $_ *> $null }
}

$baselineRows | Export-Csv -LiteralPath (Join-Path $baselineDir "startup.csv") -NoTypeInformation -Encoding utf8
$candidateRows | Export-Csv -LiteralPath (Join-Path $candidateDir "startup.csv") -NoTypeInformation -Encoding utf8

$baselineReady = @($baselineRows | ForEach-Object { [double] $_.StartupReadyMs })
$candidateReady = @($candidateRows | ForEach-Object { [double] $_.StartupReadyMs })
$baselineReachable = @($baselineRows | ForEach-Object { [double] $_.StartupReachableMs })
$candidateReachable = @($candidateRows | ForEach-Object { [double] $_.StartupReachableMs })
$readyPairDeltas = [System.Collections.Generic.List[double]]::new()
$reachablePairDeltas = [System.Collections.Generic.List[double]]::new()
foreach ($baselineRow in $baselineRows) {
    $candidateRow = $candidateRows |
            Where-Object PairCycle -eq $baselineRow.PairCycle |
            Select-Object -First 1
    if ($null -eq $candidateRow) {
        throw "Missing candidate startup pair for cycle $($baselineRow.PairCycle)."
    }
    $readyPairDeltas.Add((Get-DeltaPercent `
            ([double] $baselineRow.StartupReadyMs) `
            ([double] $candidateRow.StartupReadyMs)))
    $reachablePairDeltas.Add((Get-DeltaPercent `
            ([double] $baselineRow.StartupReachableMs) `
            ([double] $candidateRow.StartupReachableMs)))
}
$readyRegressedPairs = @($readyPairDeltas | Where-Object { $_ -gt $MaxRegressionPercent }).Count
$reachableRegressedPairs = @($reachablePairDeltas | Where-Object { $_ -gt $MaxRegressionPercent }).Count
$readyRegressedPairRate = 100.0 * $readyRegressedPairs / $readyPairDeltas.Count
$reachableRegressedPairRate = 100.0 * $reachableRegressedPairs / $reachablePairDeltas.Count
$summary = [PSCustomObject]@{
    baseline_runs = $baselineRows.Count
    candidate_runs = $candidateRows.Count
    baseline_median_ready_ms = [math]::Round((Get-Median $baselineReady), 2)
    candidate_median_ready_ms = [math]::Round((Get-Median $candidateReady), 2)
    ready_delta_pct = [math]::Round((Get-DeltaPercent (Get-Median $baselineReady) (Get-Median $candidateReady)), 2)
    baseline_ready_cv_pct = [math]::Round((Get-CvPercent $baselineReady), 2)
    candidate_ready_cv_pct = [math]::Round((Get-CvPercent $candidateReady), 2)
    baseline_median_reachable_ms = [math]::Round((Get-Median $baselineReachable), 2)
    candidate_median_reachable_ms = [math]::Round((Get-Median $candidateReachable), 2)
    reachable_delta_pct = [math]::Round((Get-DeltaPercent (Get-Median $baselineReachable) (Get-Median $candidateReachable)), 2)
    baseline_reachable_cv_pct = [math]::Round((Get-CvPercent $baselineReachable), 2)
    candidate_reachable_cv_pct = [math]::Round((Get-CvPercent $candidateReachable), 2)
    median_paired_ready_delta_pct = [math]::Round((Get-Median $readyPairDeltas), 2)
    median_paired_reachable_delta_pct = [math]::Round((Get-Median $reachablePairDeltas), 2)
    ready_regressed_pair_rate_pct = [math]::Round($readyRegressedPairRate, 2)
    reachable_regressed_pair_rate_pct = [math]::Round($reachableRegressedPairRate, 2)
}

$stable = $summary.baseline_ready_cv_pct -le $MaxCoefficientVariationPercent -and
        $summary.baseline_reachable_cv_pct -le $MaxCoefficientVariationPercent -and
        $summary.candidate_ready_cv_pct -le $MaxCoefficientVariationPercent -and
        $summary.candidate_reachable_cv_pct -le $MaxCoefficientVariationPercent
$withinRegressionBudget = $summary.median_paired_ready_delta_pct -le $MaxRegressionPercent -and
        $summary.median_paired_reachable_delta_pct -le $MaxRegressionPercent -and
        $summary.ready_regressed_pair_rate_pct -le $MaxRegressedPairRatePercent -and
        $summary.reachable_regressed_pair_rate_pct -le $MaxRegressedPairRatePercent
$gate = if (-not $stable) {
    "INCONCLUSIVE: unstable-startup"
} elseif (-not $withinRegressionBudget) {
    "FAIL: startup-regression"
} else {
    "PASS"
}
$summary | Add-Member -NotePropertyName gate -NotePropertyValue $gate
$summary | Export-Csv -LiteralPath (Join-Path $comparisonDir "startup_comparison.csv") -NoTypeInformation -Encoding utf8

$report = @(
    "# Image Startup Gate",
    "",
    "- Baseline: $BaselineImage",
    "- Candidate: $CandidateImage",
    "- Baseline JVM append: $BaselineJavaOptsAppend",
    "- Candidate JVM append: $CandidateJavaOptsAppend",
    "- Runs per image: $RepeatCount",
    "- Gate: $gate",
    "- Regression threshold: <= $MaxRegressionPercent%",
    "- Maximum regressed startup pairs: <= $MaxRegressedPairRatePercent%",
    "- Baseline and candidate stability threshold: CV <= $MaxCoefficientVariationPercent%",
    "",
    "| Metric | Baseline median ms | Candidate median ms | Delta | CV B/C | Gate |",
    "|---|---:|---:|---:|---:|---|",
    "| Internal ready | $($summary.baseline_median_ready_ms) | $($summary.candidate_median_ready_ms) | $($summary.ready_delta_pct)% | $($summary.baseline_ready_cv_pct)%/$($summary.candidate_ready_cv_pct)% | $gate |",
    "| HTTP reachable | $($summary.baseline_median_reachable_ms) | $($summary.candidate_median_reachable_ms) | $($summary.reachable_delta_pct)% | $($summary.baseline_reachable_cv_pct)%/$($summary.candidate_reachable_cv_pct)% | $gate |",
    "",
    "| Paired metric | Median delta | Pairs over budget |",
    "|---|---:|---:|",
    "| Internal ready | $($summary.median_paired_ready_delta_pct)% | $($summary.ready_regressed_pair_rate_pct)% |",
    "| HTTP reachable | $($summary.median_paired_reachable_delta_pct)% | $($summary.reachable_regressed_pair_rate_pct)% |"
)
$reportPath = Join-Path $comparisonDir "startup_comparison.md"
$report | Set-Content -LiteralPath $reportPath -Encoding utf8
Write-Output "Startup report: $reportPath"
Write-Output "Gate result: $gate"
