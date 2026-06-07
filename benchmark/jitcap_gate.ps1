param(
    [object] $ConcurrencyLevels = @(64, 256, 512),
    [string] $RuntimeProfile = "micro-rest",
    [string] $EndpointClasses = "small-json-direct,dynamic-producer-json,direct-json-writer,producer-json,raw-json",
    [int] $RepeatCount = 3,
    [int] $MinimalRepeatCount = 3,
    [string] $Duration = "10s",
    [string] $Warmup = "3s",
    [int] $SmapsDurationSeconds = 4,
    [int] $SmapsIdleSeconds = 3,
    [int] $SmapsFinalIdleSeconds = 6,
    [string] $FrameworkCodeCacheTotal = "8m",
    [double] $FrameworkCodeCacheMaxRAMPercentage = 0,
    [double] $MinRssGainMiB = 2.0,
    [double] $MaxAverageP99RegressionPercent = 10.0,
    [string] $ResultsDir = "",
    [string] $SampleDefaultResultsDir = "",
    [string] $SampleJitCapResultsDir = "",
    [string[]] $MinimalDefaultResultsDirs = @(),
    [string[]] $MinimalJitCapResultsDirs = @(),
    [int] $RandomSeed = 20260605,
    [switch] $SkipSampleBenchmark,
    [switch] $SkipMinimalSmaps,
    [switch] $SkipBuild,
    [switch] $FailOnGate
)

$ErrorActionPreference = "Stop"
if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$FrameworkRoot = Resolve-Path (Join-Path $ScriptDir "..")

if ([string]::IsNullOrWhiteSpace($ResultsDir)) {
    $ResultsDir = Join-Path $ScriptDir ("results\jitcap_gate_{0}" -f (Get-Date -Format "yyyyMMdd_HHmmss"))
}
$ResultsDir = [System.IO.Path]::GetFullPath($ResultsDir)
New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null

$ConcurrencyValues = @(
    if ($ConcurrencyLevels -is [array]) {
        $ConcurrencyLevels
    } else {
        "$ConcurrencyLevels" -split "[,\s]+"
    }
) |
    ForEach-Object { "$_".Trim() } |
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
    ForEach-Object { [int] $_ }

if ($ConcurrencyValues.Count -eq 0) {
    throw "At least one concurrency level is required."
}
if ($RepeatCount -lt 3) {
    throw "RepeatCount must be >= 3 for the jitcap gate."
}
if (-not $SkipMinimalSmaps -and $MinimalRepeatCount -lt 3) {
    throw "MinimalRepeatCount must be >= 3 unless -SkipMinimalSmaps is used."
}

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
        return 0
    }
    $match = [regex]::Match($StatusText, "(?:^|[, ]+)$([regex]::Escape($Code))=([0-9]+)")
    if ($match.Success) {
        return [int64] $match.Groups[1].Value
    }
    return 0
}

function Invoke-CheckedPowerShell {
    param([string[]] $Arguments)
    $logPath = Join-Path $ResultsDir ("child_{0}.log" -f ([Guid]::NewGuid().ToString("N")))
    $previousErrorActionPreference = $ErrorActionPreference
    $hasNativePreference = Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue
    $previousNativePreference = if ($hasNativePreference) { $PSNativeCommandUseErrorActionPreference } else { $null }
    try {
        $ErrorActionPreference = "Continue"
        if ($hasNativePreference) {
            $PSNativeCommandUseErrorActionPreference = $false
        }
        & powershell @Arguments *> $logPath
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
        if ($hasNativePreference) {
            $PSNativeCommandUseErrorActionPreference = $previousNativePreference
        }
    }
    if (Test-Path $logPath) {
        Get-Content $logPath | ForEach-Object { Write-Host $_ }
    }
    if ($exitCode -ne 0) {
        throw "powershell command failed: powershell $($Arguments -join ' ')"
    }
}

function Get-CodeCacheArgumentsForContainer {
    $args = New-Object System.Collections.Generic.List[string]
    if (-not [string]::IsNullOrWhiteSpace($FrameworkCodeCacheTotal)) {
        $args.Add("-FrameworkCodeCacheTotal")
        $args.Add($FrameworkCodeCacheTotal)
    }
    if ($FrameworkCodeCacheMaxRAMPercentage -gt 0) {
        $args.Add("-FrameworkCodeCacheMaxRAMPercentage")
        $args.Add("$FrameworkCodeCacheMaxRAMPercentage")
    }
    return [string[]] $args
}

function Get-CodeCacheArgumentsForSmaps {
    $args = New-Object System.Collections.Generic.List[string]
    if (-not [string]::IsNullOrWhiteSpace($FrameworkCodeCacheTotal)) {
        $args.Add("-CodeCacheTotal")
        $args.Add($FrameworkCodeCacheTotal)
    }
    if ($FrameworkCodeCacheMaxRAMPercentage -gt 0) {
        $args.Add("-CodeCacheMaxRAMPercentage")
        $args.Add("$FrameworkCodeCacheMaxRAMPercentage")
    }
    return [string[]] $args
}

function Invoke-SampleBenchmark {
    param([string] $Label, [switch] $JitCap)

    $targetDir = Join-Path $ResultsDir "sample_$Label"
    $args = New-Object System.Collections.Generic.List[string]
    foreach ($arg in @(
        "-ExecutionPolicy", "Bypass",
        "-File", (Join-Path $ScriptDir "container_benchmark.ps1"),
        "-RuntimeProfile", $RuntimeProfile,
        "-EndpointClasses", $EndpointClasses,
        "-ConcurrencyLevels", ($ConcurrencyValues -join ","),
        "-Duration", $Duration,
        "-Warmup", $Warmup,
        "-RepeatCount", "$RepeatCount",
        "-RandomSeed", "$RandomSeed",
        "-FrameworkOnly",
        "-ResultsDir", $targetDir
    )) {
        $args.Add([string] $arg)
    }
    if ($JitCap) {
        foreach ($arg in (Get-CodeCacheArgumentsForContainer)) {
            $args.Add([string] $arg)
        }
    }
    if ($SkipBuild) {
        $args.Add("-SkipBuild")
    }
    Invoke-CheckedPowerShell -Arguments ([string[]] $args)
    return $targetDir
}

function Invoke-MinimalSmaps {
    param([string] $Label, [switch] $JitCap)

    $dirs = New-Object System.Collections.Generic.List[string]
    $minimalImageBuilt = $false
    for ($run = 1; $run -le $MinimalRepeatCount; $run++) {
        $targetDir = Join-Path $ResultsDir ("minimal_{0}_r{1}" -f $Label, $run)
        $args = New-Object System.Collections.Generic.List[string]
        foreach ($arg in @(
            "-ExecutionPolicy", "Bypass",
            "-File", (Join-Path $ScriptDir "linux_smaps_breakdown.ps1"),
            "-AppMode", "minimal",
            "-RuntimeProfile", $RuntimeProfile,
            "-ConcurrencyValues", ($ConcurrencyValues -join ","),
            "-DurationSeconds", "$SmapsDurationSeconds",
            "-IdleSeconds", "$SmapsIdleSeconds",
            "-FinalIdleSeconds", "$SmapsFinalIdleSeconds",
            "-HostPort", "$(18190 + $run)",
            "-ResultsDir", $targetDir
        )) {
            $args.Add([string] $arg)
        }
        if ($JitCap) {
            foreach ($arg in (Get-CodeCacheArgumentsForSmaps)) {
                $args.Add([string] $arg)
            }
        }
        if ($SkipBuild -or $minimalImageBuilt) {
            $args.Add("-SkipBuild")
        }
        Invoke-CheckedPowerShell -Arguments ([string[]] $args)
        $minimalImageBuilt = $true
        $dirs.Add($targetDir)
    }
    return [string[]] $dirs
}

function Get-SampleSummary {
    param([string] $ResultsPath)

    $csv = Join-Path $ResultsPath "results.csv"
    if (-not (Test-Path $csv)) {
        throw "Missing sample benchmark results: $csv"
    }
    $items = @(Import-Csv $csv)
    $items |
        Group-Object EndpointClass, Concurrency |
        ForEach-Object {
            $group = @($_.Group)
            $first = $group[0]
            $status200 = [int64] 0
            $status503 = [int64] 0
            foreach ($row in $group) {
                $status200 += Get-StatusCount -StatusText $row.HttpStatus -Code "200"
                $status503 += Get-StatusCount -StatusText $row.HttpStatus -Code "503"
            }
            $totalStatus = $status200 + $status503
            [PSCustomObject]@{
                endpoint_class = $first.EndpointClass
                concurrency = [int] $first.Concurrency
                avg_rps = [math]::Round((($group | ForEach-Object { Convert-ToDoubleValue $_.Rps } | Measure-Object -Average).Average), 2)
                avg_p99_ms = [math]::Round((($group | ForEach-Object { Convert-ToDoubleValue $_.P99 } | Measure-Object -Average).Average), 2)
                max_p99_ms = [math]::Round((($group | ForEach-Object { Convert-ToDoubleValue $_.P99 } | Measure-Object -Maximum).Maximum), 2)
                avg_rss_after_mib = [math]::Round((($group | ForEach-Object { Convert-ToDoubleValue $_.RssAfterMiB } | Measure-Object -Average).Average), 2)
                max_container_mem_mib = [math]::Round((($group | ForEach-Object { Convert-ToDoubleValue $_.MaxContainerMemMiB } | Measure-Object -Maximum).Maximum), 2)
                status_503_rate_pct = [math]::Round($(if ($totalStatus -gt 0) { 100.0 * $status503 / $totalStatus } else { 0.0 }), 2)
                runs = $group.Count
            }
        }
}

function Get-MinimalSmapsSummary {
    param([string[]] $RunDirs)

    $rows = New-Object System.Collections.Generic.List[object]
    foreach ($dir in $RunDirs) {
        $csv = Join-Path $dir "linux_smaps_summary.csv"
        if (-not (Test-Path $csv)) {
            throw "Missing smaps summary: $csv"
        }
        $final = Import-Csv $csv | Where-Object { $_.phase -eq "99_final_idle" } | Select-Object -First 1
        if ($null -eq $final) {
            throw "Missing 99_final_idle row in $csv"
        }
        $rows.Add($final)
    }

    function AverageField([string] $Field) {
        return [math]::Round((($rows | ForEach-Object { Convert-ToDoubleValue $_.$Field } | Measure-Object -Average).Average), 3)
    }

    [PSCustomObject]@{
        runs = $rows.Count
        cgroup_current_mib = AverageField "cgroup_current_mib"
        cgroup_anon_mib = AverageField "cgroup_anon_mib"
        heap_used_mib = AverageField "heap_used_mib"
        non_heap_used_mib = AverageField "non_heap_used_mib"
        non_heap_committed_mib = AverageField "non_heap_committed_mib"
        jit_code_committed_mib = AverageField "jit_code_committed_mib"
        anon_residual_mib = AverageField "anon_residual_mib"
        anon_residual_after_stack_budget_mib = AverageField "anon_residual_after_stack_budget_mib"
        loaded_classes = [math]::Round((($rows | ForEach-Object { Convert-ToDoubleValue $_.loaded_classes } | Measure-Object -Average).Average), 0)
        linux_threads = [math]::Round((($rows | ForEach-Object { Convert-ToDoubleValue $_.linux_threads } | Measure-Object -Average).Average), 0)
    }
}

function Compare-SampleSummaries {
    param([object[]] $DefaultRows, [object[]] $JitCapRows)

    foreach ($row in $DefaultRows) {
        $candidate = $JitCapRows |
            Where-Object { $_.endpoint_class -eq $row.endpoint_class -and $_.concurrency -eq $row.concurrency } |
            Select-Object -First 1
        if ($null -eq $candidate) {
            continue
        }
        $p99RegressionPct = if ($row.avg_p99_ms -gt 0) {
            100.0 * ($candidate.avg_p99_ms - $row.avg_p99_ms) / $row.avg_p99_ms
        } else {
            0.0
        }
        $rpsDeltaPct = if ($row.avg_rps -gt 0) {
            100.0 * ($candidate.avg_rps - $row.avg_rps) / $row.avg_rps
        } else {
            0.0
        }
        [PSCustomObject]@{
            endpoint_class = $row.endpoint_class
            concurrency = $row.concurrency
            default_rps = $row.avg_rps
            jitcap_rps = $candidate.avg_rps
            rps_delta_pct = [math]::Round($rpsDeltaPct, 2)
            default_p99_ms = $row.avg_p99_ms
            jitcap_p99_ms = $candidate.avg_p99_ms
            p99_delta_ms = [math]::Round($candidate.avg_p99_ms - $row.avg_p99_ms, 2)
            p99_regression_pct = [math]::Round($p99RegressionPct, 2)
            default_rss_after_mib = $row.avg_rss_after_mib
            jitcap_rss_after_mib = $candidate.avg_rss_after_mib
            rss_after_delta_mib = [math]::Round($candidate.avg_rss_after_mib - $row.avg_rss_after_mib, 2)
            default_503_rate_pct = $row.status_503_rate_pct
            jitcap_503_rate_pct = $candidate.status_503_rate_pct
        }
    }
}

$sampleDefaultDir = $SampleDefaultResultsDir
$sampleJitCapDir = $SampleJitCapResultsDir
$minimalDefaultDirs = @($MinimalDefaultResultsDirs)
$minimalJitCapDirs = @($MinimalJitCapResultsDirs)

if (-not $SkipSampleBenchmark) {
    if ([string]::IsNullOrWhiteSpace($sampleDefaultDir) -or [string]::IsNullOrWhiteSpace($sampleJitCapDir)) {
        $sampleDefaultDir = Invoke-SampleBenchmark -Label "default"
        $sampleJitCapDir = Invoke-SampleBenchmark -Label "jitcap" -JitCap
    }
}

if (-not $SkipMinimalSmaps) {
    if ($minimalDefaultDirs.Count -eq 0 -or $minimalJitCapDirs.Count -eq 0) {
        $minimalDefaultDirs = @(Invoke-MinimalSmaps -Label "default")
        $minimalJitCapDirs = @(Invoke-MinimalSmaps -Label "jitcap" -JitCap)
    }
}

$sampleComparison = @()
$minimalDefault = $null
$minimalJitCap = $null
if (-not $SkipSampleBenchmark) {
    $sampleComparison = @(Compare-SampleSummaries `
        -DefaultRows @(Get-SampleSummary -ResultsPath $sampleDefaultDir) `
        -JitCapRows @(Get-SampleSummary -ResultsPath $sampleJitCapDir))
}
if (-not $SkipMinimalSmaps) {
    $minimalDefault = Get-MinimalSmapsSummary -RunDirs $minimalDefaultDirs
    $minimalJitCap = Get-MinimalSmapsSummary -RunDirs $minimalJitCapDirs
}

$rssGain = if ($null -ne $minimalDefault -and $null -ne $minimalJitCap) {
    [math]::Round($minimalDefault.cgroup_current_mib - $minimalJitCap.cgroup_current_mib, 3)
} else {
    0.0
}
$rssPass = $SkipMinimalSmaps -or ($rssGain -ge $MinRssGainMiB)
$p99Failures = @($sampleComparison | Where-Object { $_.p99_regression_pct -gt $MaxAverageP99RegressionPercent })
$dynamicHotPathFailures = @($sampleComparison | Where-Object {
    $_.endpoint_class -in @("dynamic-producer-json", "dynamic-dto-json") `
            -and $_.concurrency -in @(256, 512) `
            -and $_.jitcap_p99_ms -gt $_.default_p99_ms
})
$optionalPass = $rssPass -and ($p99Failures.Count -eq 0)
$defaultCandidate = $optionalPass -and ($dynamicHotPathFailures.Count -eq 0)

$reportPath = Join-Path $ResultsDir "jitcap_gate_report.md"
$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("# JIT-cap Gate Report")
$lines.Add("")
$lines.Add("- Date: $(Get-Date -Format o)")
$lines.Add("- Runtime profile: $RuntimeProfile")
$lines.Add("- Code cache total: $FrameworkCodeCacheTotal")
if ($FrameworkCodeCacheMaxRAMPercentage -gt 0) {
    $lines.Add("- Code cache MaxRAM percentage: $FrameworkCodeCacheMaxRAMPercentage")
}
$lines.Add("- Requested concurrency: $($ConcurrencyValues -join ', ')")
if ($sampleComparison.Count -gt 0) {
    $actualSampleConcurrency = @($sampleComparison | ForEach-Object { $_.concurrency } | Sort-Object -Unique)
    $lines.Add("- Actual sample evidence concurrency: $($actualSampleConcurrency -join ', ')")
}
$lines.Add("- Configured sample repeat count: $RepeatCount")
$lines.Add("- Configured minimal smaps repeat count: $MinimalRepeatCount")
if ($null -ne $minimalDefault -and $null -ne $minimalJitCap) {
    $lines.Add("- Actual minimal default evidence runs: $($minimalDefault.runs)")
    $lines.Add("- Actual minimal jitcap evidence runs: $($minimalJitCap.runs)")
}
$lines.Add("- Min RSS gain threshold: $MinRssGainMiB MiB")
$lines.Add("- Max avg p99 regression threshold: $MaxAverageP99RegressionPercent%")
$lines.Add("")
$lines.Add("## Decision")
$lines.Add("")
$lines.Add("| Gate | Result |")
$lines.Add("|---|---|")
$lines.Add("| Optional jitcap usable | $(if ($optionalPass) { "PASS" } else { "FAIL" }) |")
$lines.Add("| Default profile candidate | $(if ($defaultCandidate) { "PASS" } else { "FAIL" }) |")
$lines.Add("| Minimal RSS gain | $rssGain MiB |")
$lines.Add("| p99 regression failures | $($p99Failures.Count) |")
$lines.Add("| dynamic heavy JSON c256/c512 regressions | $($dynamicHotPathFailures.Count) |")
$lines.Add("")
if (-not $defaultCandidate) {
    $lines.Add("Default-candidate rule: if dynamic heavy JSON c256/c512 regresses, keep `jitcap` optional and use `JsonProducerResponse` or direct writer for hot heavy JSON.")
    $lines.Add("")
}
if (-not $SkipMinimalSmaps) {
    $lines.Add("## Minimal Production RSS/Anon")
    $lines.Add("")
    $lines.Add("| Metric | Default | JIT-cap | Delta |")
    $lines.Add("|---|---:|---:|---:|")
    foreach ($metric in @(
        "cgroup_current_mib",
        "cgroup_anon_mib",
        "non_heap_committed_mib",
        "jit_code_committed_mib",
        "anon_residual_mib",
        "anon_residual_after_stack_budget_mib",
        "loaded_classes",
        "linux_threads"
    )) {
        $defaultValue = [double] $minimalDefault.$metric
        $jitCapValue = [double] $minimalJitCap.$metric
        $lines.Add("| $metric | $defaultValue | $jitCapValue | $([math]::Round($jitCapValue - $defaultValue, 3)) |")
    }
    $lines.Add("")
}
if (-not $SkipSampleBenchmark) {
    $lines.Add("## Sample App Latency/P99")
    $lines.Add("")
    $lines.Add("| Class | C | Default RPS | JIT-cap RPS | RPS delta % | Default p99 | JIT-cap p99 | p99 regression % | Default 503 % | JIT-cap 503 % |")
    $lines.Add("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    foreach ($row in ($sampleComparison | Sort-Object endpoint_class, concurrency)) {
        $lines.Add("| $($row.endpoint_class) | $($row.concurrency) | $($row.default_rps) | $($row.jitcap_rps) | $($row.rps_delta_pct) | $($row.default_p99_ms) | $($row.jitcap_p99_ms) | $($row.p99_regression_pct) | $($row.default_503_rate_pct) | $($row.jitcap_503_rate_pct) |")
    }
    $lines.Add("")
}
$lines.Add("## Result Paths")
$lines.Add("")
if ($sampleDefaultDir) { $lines.Add("- Sample default: $sampleDefaultDir") }
if ($sampleJitCapDir) { $lines.Add("- Sample jitcap: $sampleJitCapDir") }
foreach ($dir in $minimalDefaultDirs) { $lines.Add("- Minimal default: $dir") }
foreach ($dir in $minimalJitCapDirs) { $lines.Add("- Minimal jitcap: $dir") }
$lines | Set-Content -Path $reportPath -Encoding UTF8

$jsonPath = Join-Path $ResultsDir "jitcap_gate_summary.json"
[PSCustomObject]@{
    optional_jitcap_usable = $optionalPass
    default_profile_candidate = $defaultCandidate
    minimal_rss_gain_mib = $rssGain
    p99_regression_failures = $p99Failures.Count
    dynamic_heavy_json_regressions = $dynamicHotPathFailures.Count
    report = $reportPath
} | ConvertTo-Json -Depth 4 | Set-Content -Path $jsonPath -Encoding UTF8

Write-Host "jitcap gate report: $reportPath"
if ($FailOnGate -and -not $optionalPass) {
    exit 1
}
