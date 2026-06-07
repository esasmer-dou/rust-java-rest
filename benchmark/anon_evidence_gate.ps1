param(
    [string[]] $Profiles = @("micro-rest", "micro-rest-plus", "micro-dubbo"),
    [ValidateSet("sample", "minimal")]
    [string] $AppMode = "minimal",
    [string[]] $ConcurrencyValues = @("64", "256"),
    [string[]] $EndpointSpecs = @(
        "small-direct|/api/v1/candidates/direct",
        "direct-heavy|/api/v1/heavy?items=100",
        "producer-heavy|/api/v1/heavy/producer?items=100",
        "dynamic-producer|/api/v1/heavy/dto?items=100",
        "raw-heavy|/api/v1/heavy/raw"
    ),
    [int] $DurationSeconds = 5,
    [int] $IdleSeconds = 3,
    [int] $FinalIdleSeconds = 12,
    [int] $TrimFinalIdleSeconds = 95,
    [string[]] $TrimFinalIdleSnapshotSeconds = @("35", "95"),
    [int] $JavacoreFinalIdleSeconds = 12,
    [int] $HostPort = 18186,
    [string] $ResultsDir = "",
    [string] $TrimOnJavaOpts = "-Dreactor.rust.native-trim.enabled=true -Dreactor.rust.native-trim.initial-delay-ms=30000 -Dreactor.rust.native-trim.interval-ms=60000 -Dreactor.rust.native-trim.min-idle-ms=10000 -Dreactor.rust.native-trim.max-active-connections=0 -Dreactor.rust.native-trim.max-active-requests=0 -Dreactor.rust.native-trim.retain-small=16 -Dreactor.rust.native-trim.retain-medium=0 -Dreactor.rust.native-trim.retain-large=0 -Dreactor.rust.native-trim.retain-huge=0 -Dreactor.rust.native-trim.allocator-trim-enabled=true",
    [string] $TrimOffJavaOpts = "-Dreactor.rust.native-trim.enabled=false",
    [switch] $Quick,
    [switch] $SkipInitialBuild,
    [switch] $SkipProfileRuns,
    [switch] $SkipTrimAb,
    [switch] $SkipJavacore
)

$ErrorActionPreference = "Stop"
if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$SmapsScript = Join-Path $ScriptDir "linux_smaps_breakdown.ps1"

if (-not (Test-Path $SmapsScript)) {
    throw "Missing linux_smaps_breakdown.ps1 at $SmapsScript"
}

if ($Quick) {
    if (-not $PSBoundParameters.ContainsKey("Profiles")) {
        $Profiles = @("micro-rest", "micro-rest-plus", "micro-dubbo")
    }
    if (-not $PSBoundParameters.ContainsKey("ConcurrencyValues")) {
        $ConcurrencyValues = @("64")
    }
    if (-not $PSBoundParameters.ContainsKey("EndpointSpecs")) {
        $EndpointSpecs = @(
            "small-direct|/api/v1/candidates/direct",
            "producer-heavy|/api/v1/heavy/producer?items=100",
            "raw-heavy|/api/v1/heavy/raw"
        )
    }
    if (-not $PSBoundParameters.ContainsKey("DurationSeconds")) {
        $DurationSeconds = 3
    }
    if (-not $PSBoundParameters.ContainsKey("IdleSeconds")) {
        $IdleSeconds = 1
    }
    if (-not $PSBoundParameters.ContainsKey("FinalIdleSeconds")) {
        $FinalIdleSeconds = 4
    }
    if (-not $PSBoundParameters.ContainsKey("TrimFinalIdleSeconds")) {
        $TrimFinalIdleSeconds = 35
    }
    if (-not $PSBoundParameters.ContainsKey("TrimFinalIdleSnapshotSeconds")) {
        $TrimFinalIdleSnapshotSeconds = @("35")
    }
    if (-not $PSBoundParameters.ContainsKey("JavacoreFinalIdleSeconds")) {
        $JavacoreFinalIdleSeconds = 4
    }
}

$Profiles = @(
    $Profiles |
        ForEach-Object { "$_" -split "," } |
        ForEach-Object { $_.Trim() } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
)
if ($Profiles.Count -eq 0) {
    throw "At least one profile is required."
}

$ConcurrencyValues = @(
    $ConcurrencyValues |
        ForEach-Object { "$_" -split "," } |
        ForEach-Object { $_.Trim() } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        ForEach-Object { [int] $_ }
)
if ($ConcurrencyValues.Count -eq 0) {
    throw "At least one concurrency value is required."
}

$TrimFinalIdleSnapshotSeconds = @(
    $TrimFinalIdleSnapshotSeconds |
        ForEach-Object { "$_" -split "," } |
        ForEach-Object { $_.Trim() } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        ForEach-Object { [int] $_ }
)

if ([string]::IsNullOrWhiteSpace($ResultsDir)) {
    $ResultsDir = Join-Path $ScriptDir ("results\anon_evidence_gate_{0}_{1}" -f $AppMode, (Get-Date -Format "yyyyMMdd_HHmmss"))
}
$ResultsDir = [System.IO.Path]::GetFullPath($ResultsDir)
New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null

function Convert-ToDouble {
    param($Value)
    if ($null -eq $Value) {
        return 0.0
    }
    $text = ([string] $Value).Trim().Replace(',', '.')
    if ([string]::IsNullOrWhiteSpace($text)) {
        return 0.0
    }
    return [double]::Parse($text, [System.Globalization.CultureInfo]::InvariantCulture)
}

function Get-StatusCount {
    param([string] $Statuses, [string] $Status)
    if ([string]::IsNullOrWhiteSpace($Statuses)) {
        return 0
    }
    try {
        $json = $Statuses | ConvertFrom-Json
        $prop = $json.PSObject.Properties[$Status]
        if ($null -eq $prop) {
            return 0
        }
        return [int64] $prop.Value
    } catch {
        return 0
    }
}

function Average {
    param($Items, [string] $Property)
    $values = @($Items | ForEach-Object { Convert-ToDouble $_.$Property })
    if ($values.Count -eq 0) {
        return 0.0
    }
    return [Math]::Round((($values | Measure-Object -Average).Average), 3)
}

function MaxValue {
    param($Items, [string] $Property)
    $values = @($Items | ForEach-Object { Convert-ToDouble $_.$Property })
    if ($values.Count -eq 0) {
        return 0.0
    }
    return [Math]::Round((($values | Measure-Object -Maximum).Maximum), 3)
}

function Get-FinalIdleOrder {
    param([string] $Phase)
    if ([string]::IsNullOrWhiteSpace($Phase)) {
        return -1
    }
    if ($Phase -eq "99_final_idle") {
        return 0
    }
    if ($Phase -match "^99_final_idle_(\d+)s$") {
        return [int] $Matches[1]
    }
    return -1
}

function Import-RunSummary {
    param(
        [string] $CaseName,
        [string] $RuntimeProfile,
        [string] $Variant,
        [string] $RunDir
    )

    $summaryPath = Join-Path $RunDir "linux_smaps_summary.csv"
    $loadPath = Join-Path $RunDir "load_results.csv"
    if (-not (Test-Path $summaryPath)) {
        throw "Missing summary CSV: $summaryPath"
    }
    if (-not (Test-Path $loadPath)) {
        throw "Missing load CSV: $loadPath"
    }

    foreach ($row in Import-Csv $summaryPath) {
        $row | Add-Member -NotePropertyName case -NotePropertyValue $CaseName
        $row | Add-Member -NotePropertyName runtime_profile -NotePropertyValue $RuntimeProfile
        $row | Add-Member -NotePropertyName variant -NotePropertyValue $Variant
        $row | Add-Member -NotePropertyName run_dir -NotePropertyValue $RunDir
        $script:MemoryRows.Add($row)
    }
    foreach ($row in Import-Csv $loadPath) {
        $row | Add-Member -NotePropertyName case -NotePropertyValue $CaseName
        $row | Add-Member -NotePropertyName runtime_profile -NotePropertyValue $RuntimeProfile
        $row | Add-Member -NotePropertyName variant -NotePropertyValue $Variant
        $row | Add-Member -NotePropertyName run_dir -NotePropertyValue $RunDir
        $status200 = Get-StatusCount -Statuses $row.statuses -Status "200"
        $status503 = Get-StatusCount -Statuses $row.statuses -Status "503"
        $status503Percent = 0.0
        if ([int64] $row.requests -gt 0) {
            $status503Percent = [Math]::Round(([double] $status503 * 100.0) / [double] $row.requests, 3)
        }
        $row | Add-Member -NotePropertyName status_200 -NotePropertyValue $status200
        $row | Add-Member -NotePropertyName status_503 -NotePropertyValue $status503
        $row | Add-Member -NotePropertyName status_503_percent -NotePropertyValue $status503Percent
        $script:LoadRows.Add($row)
    }
}

function Invoke-SmapsRun {
    param(
        [string] $CaseName,
        [string] $RuntimeProfile,
        [string] $Variant,
        [string] $ExtraJavaOpts,
        [int] $RunFinalIdleSeconds,
        [int[]] $RunFinalIdleSnapshotSeconds = @(),
        [switch] $CollectJavacore,
        [bool] $SkipBuild
    )

    $safeCase = ($CaseName -replace "[^A-Za-z0-9_.-]", "_")
    $safeProfile = ($RuntimeProfile -replace "[^A-Za-z0-9_.-]", "_")
    $safeVariant = ($Variant -replace "[^A-Za-z0-9_.-]", "_")
    $runDir = Join-Path $ResultsDir ("{0}_{1}_{2}" -f $safeCase, $safeProfile, $safeVariant)

    $runArgs = @(
        "-ExecutionPolicy", "Bypass",
        "-File", $SmapsScript,
        "-AppMode", $AppMode,
        "-RuntimeProfile", $RuntimeProfile,
        "-ConcurrencyValues", ($ConcurrencyValues -join ","),
        "-EndpointSpecs", ($EndpointSpecs -join ","),
        "-DurationSeconds", "$DurationSeconds",
        "-IdleSeconds", "$IdleSeconds",
        "-FinalIdleSeconds", "$RunFinalIdleSeconds",
        "-HostPort", "$HostPort",
        "-ResultsDir", $runDir
    )

    if (-not [string]::IsNullOrWhiteSpace($ExtraJavaOpts)) {
        $runArgs += @("-ExtraJavaOpts", $ExtraJavaOpts)
    }
    if ($RunFinalIdleSnapshotSeconds.Count -gt 0) {
        $runArgs += @("-FinalIdleSnapshotSeconds", ($RunFinalIdleSnapshotSeconds -join ","))
    }
    if ($CollectJavacore) {
        $runArgs += "-CollectJavacore"
    }
    if ($SkipBuild) {
        $runArgs += "-SkipBuild"
    }

    $script:RunRows.Add([PSCustomObject]@{
        case = $CaseName
        runtime_profile = $RuntimeProfile
        variant = $Variant
        results_dir = $runDir
        skip_build = $SkipBuild
        collect_javacore = [bool] $CollectJavacore
        extra_java_opts = $ExtraJavaOpts
    })

    Write-Host ("[anon-gate] case={0} profile={1} variant={2} skipBuild={3}" -f $CaseName, $RuntimeProfile, $Variant, $SkipBuild)
    & powershell @runArgs | ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) {
        throw "linux_smaps_breakdown failed for case=$CaseName profile=$RuntimeProfile variant=$Variant"
    }

    Import-RunSummary -CaseName $CaseName -RuntimeProfile $RuntimeProfile -Variant $Variant -RunDir $runDir
    return $runDir
}

function Build-MemoryAggregate {
    $result = New-Object 'System.Collections.Generic.List[object]'
    foreach ($group in ($MemoryRows | Where-Object { $_.phase -eq "99_final_idle" -or $_.phase -like "99_final_idle_*s" } | Group-Object case,runtime_profile,variant)) {
        $allFinalRows = @($group.Group)
        $maxOrder = ($allFinalRows | ForEach-Object { Get-FinalIdleOrder $_.phase } | Measure-Object -Maximum).Maximum
        $rows = @($allFinalRows | Where-Object { (Get-FinalIdleOrder $_.phase) -eq $maxOrder })
        $first = $rows[0]
        $result.Add([PSCustomObject]@{
            case = $first.case
            runtime_profile = $first.runtime_profile
            variant = $first.variant
            final_phase = $first.phase
            final_phase_count = $rows.Count
            final_smaps_rss_mib = Average $rows "smaps_rss_mib"
            final_cgroup_current_mib = Average $rows "cgroup_current_mib"
            final_cgroup_anon_mib = Average $rows "cgroup_anon_mib"
            final_heap_used_mib = Average $rows "heap_used_mib"
            final_jit_code_used_mib = Average $rows "jit_code_used_mib"
            final_class_metadata_used_mib = Average $rows "class_metadata_used_mib"
            final_non_heap_other_used_mib = Average $rows "non_heap_other_used_mib"
            final_direct_buffer_mib = Average $rows "direct_buffer_mib"
            final_rust_accounted_mib = Average $rows "rust_accounted_mib"
            final_thread_stack_budget_mib = Average $rows "thread_stack_budget_mib"
            final_anon_residual_mib = Average $rows "anon_residual_mib"
            final_native_http_requests_total = Average $rows "native_http_requests_total"
            final_native_http_user_requests_total = Average $rows "native_http_user_requests_total"
            trim_success = Average $rows "native_trim_success"
            trim_skipped_active = Average $rows "native_trim_skipped_active"
            trim_skipped_not_idle = Average $rows "native_trim_skipped_not_idle"
            trim_last_duration_ms = Average $rows "native_trim_last_duration_ms"
        })
    }
    return $result
}

function Build-PeakAggregate {
    $result = New-Object 'System.Collections.Generic.List[object]'
    foreach ($group in ($MemoryRows | Group-Object case,runtime_profile,variant)) {
        $rows = @($group.Group)
        $first = $rows[0]
        $result.Add([PSCustomObject]@{
            case = $first.case
            runtime_profile = $first.runtime_profile
            variant = $first.variant
            peak_smaps_rss_mib = MaxValue $rows "smaps_rss_mib"
            peak_cgroup_current_mib = MaxValue $rows "cgroup_current_mib"
            peak_cgroup_anon_mib = MaxValue $rows "cgroup_anon_mib"
            peak_anon_residual_mib = MaxValue $rows "anon_residual_mib"
        })
    }
    return $result
}

function Build-LoadAggregate {
    $result = New-Object 'System.Collections.Generic.List[object]'
    foreach ($group in ($LoadRows | Group-Object case,runtime_profile,variant,endpoint,concurrency)) {
        $rows = @($group.Group)
        $first = $rows[0]
        $totalRequests = 0L
        $total200 = 0L
        $total503 = 0L
        foreach ($row in $rows) {
            $totalRequests += [int64] $row.requests
            $total200 += [int64] $row.status_200
            $total503 += [int64] $row.status_503
        }
        $result.Add([PSCustomObject]@{
            case = $first.case
            runtime_profile = $first.runtime_profile
            variant = $first.variant
            endpoint = $first.endpoint
            concurrency = [int] $first.concurrency
            avg_rps = Average $rows "rps"
            avg_p99_ms = Average $rows "p99_ms"
            avg_p95_ms = Average $rows "p95_ms"
            avg_503_percent = Average $rows "status_503_percent"
            total_requests = $totalRequests
            total_200 = $total200
            total_503 = $total503
            phases = $rows.Count
        })
    }
    return $result
}

function Write-GateReport {
    param($MemoryAggregate, $PeakAggregate, $LoadAggregate)

    $runPath = Join-Path $ResultsDir "anon_evidence_gate_runs.csv"
    $memoryPath = Join-Path $ResultsDir "anon_evidence_memory.csv"
    $peakPath = Join-Path $ResultsDir "anon_evidence_peaks.csv"
    $loadPath = Join-Path $ResultsDir "anon_evidence_load.csv"
    $allMemoryPath = Join-Path $ResultsDir "anon_evidence_memory_all_rows.csv"
    $allLoadPath = Join-Path $ResultsDir "anon_evidence_load_all_rows.csv"
    $reportPath = Join-Path $ResultsDir "anon_evidence_gate_report.md"

    $RunRows | Export-Csv -Path $runPath -NoTypeInformation -Encoding UTF8
    $MemoryAggregate | Export-Csv -Path $memoryPath -NoTypeInformation -Encoding UTF8
    $PeakAggregate | Export-Csv -Path $peakPath -NoTypeInformation -Encoding UTF8
    $LoadAggregate | Export-Csv -Path $loadPath -NoTypeInformation -Encoding UTF8
    $MemoryRows | Export-Csv -Path $allMemoryPath -NoTypeInformation -Encoding UTF8
    $LoadRows | Export-Csv -Path $allLoadPath -NoTypeInformation -Encoding UTF8

    $trimOff = $MemoryAggregate | Where-Object { $_.case -eq "trim-ab" -and $_.variant -eq "trim-off" } | Select-Object -First 1
    $trimOn = $MemoryAggregate | Where-Object { $_.case -eq "trim-ab" -and $_.variant -eq "trim-on-conservative" } | Select-Object -First 1
    $trimAnonDelta = 0.0
    $trimCurrentDelta = 0.0
    $trimResidualDelta = 0.0
    if ($trimOff -and $trimOn) {
        $trimAnonDelta = [Math]::Round($trimOn.final_cgroup_anon_mib - $trimOff.final_cgroup_anon_mib, 3)
        $trimCurrentDelta = [Math]::Round($trimOn.final_cgroup_current_mib - $trimOff.final_cgroup_current_mib, 3)
        $trimResidualDelta = [Math]::Round($trimOn.final_anon_residual_mib - $trimOff.final_anon_residual_mib, 3)
    }

    $lines = New-Object 'System.Collections.Generic.List[string]'
    $lines.Add("# Anon Evidence Gate")
    $lines.Add("")
    $lines.Add("- Date: $(Get-Date -Format o)")
    $lines.Add("- App mode: $AppMode")
    $lines.Add("- Profiles: $($Profiles -join ',')")
    $lines.Add("- Concurrency: $($ConcurrencyValues -join ',')")
    $lines.Add("- Duration per load phase: ${DurationSeconds}s")
    $lines.Add("- Idle between phases: ${IdleSeconds}s")
    $lines.Add("- Final idle: ${FinalIdleSeconds}s")
    $lines.Add("- Trim final idle: ${TrimFinalIdleSeconds}s")
    $lines.Add("- Trim snapshots: $($TrimFinalIdleSnapshotSeconds -join ',')")
    $lines.Add("")
    $lines.Add("## Gate Decision Signal")
    $lines.Add("")
    $lines.Add("| Signal | Value |")
    $lines.Add("|---|---:|")
    $lines.Add("| Conservative trim cgroup-current delta, on - off | $trimCurrentDelta MiB |")
    $lines.Add("| Conservative trim cgroup-anon delta, on - off | $trimAnonDelta MiB |")
    $lines.Add("| Conservative trim residual-anon delta, on - off | $trimResidualDelta MiB |")
    $lines.Add("")
    $lines.Add("BEST: use this report to choose the next code target. Do not tune heap alone when residual anon, thread/native pool, or Java-heavy object graph is the dominant area.")
    $lines.Add("")
    $lines.Add("## Final Idle Attribution")
    $lines.Add("")
    $lines.Add("| Case | Profile | Variant | Final phase | Current | Anon | Heap | JIT | Class meta | Direct buffer | Rust accounted | Stack budget | Residual anon | Total req | User req | Trim success |")
    $lines.Add("|---|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    foreach ($row in ($MemoryAggregate | Sort-Object case,runtime_profile,variant)) {
        $lines.Add("| $($row.case) | $($row.runtime_profile) | $($row.variant) | $($row.final_phase) | $($row.final_cgroup_current_mib) | $($row.final_cgroup_anon_mib) | $($row.final_heap_used_mib) | $($row.final_jit_code_used_mib) | $($row.final_class_metadata_used_mib) | $($row.final_direct_buffer_mib) | $($row.final_rust_accounted_mib) | $($row.final_thread_stack_budget_mib) | $($row.final_anon_residual_mib) | $($row.final_native_http_requests_total) | $($row.final_native_http_user_requests_total) | $($row.trim_success) |")
    }
    $lines.Add("")
    $lines.Add("## Peak Memory")
    $lines.Add("")
    $lines.Add("| Case | Profile | Variant | Peak current | Peak anon | Peak smaps RSS | Peak residual anon |")
    $lines.Add("|---|---|---|---:|---:|---:|---:|")
    foreach ($row in ($PeakAggregate | Sort-Object case,runtime_profile,variant)) {
        $lines.Add("| $($row.case) | $($row.runtime_profile) | $($row.variant) | $($row.peak_cgroup_current_mib) | $($row.peak_cgroup_anon_mib) | $($row.peak_smaps_rss_mib) | $($row.peak_anon_residual_mib) |")
    }
    $lines.Add("")
    $lines.Add("## Load Signal")
    $lines.Add("")
    $lines.Add("| Case | Profile | Variant | Endpoint | C | RPS | p99 | 503% |")
    $lines.Add("|---|---|---|---|---:|---:|---:|---:|")
    foreach ($row in ($LoadAggregate | Sort-Object case,runtime_profile,variant,endpoint,concurrency)) {
        $lines.Add("| $($row.case) | $($row.runtime_profile) | $($row.variant) | $($row.endpoint) | $($row.concurrency) | $($row.avg_rps) | $($row.avg_p99_ms) | $($row.avg_503_percent) |")
    }
    $lines.Add("")
    $lines.Add("## Output Files")
    $lines.Add("")
    $lines.Add("- Runs: $runPath")
    $lines.Add("- Final memory: $memoryPath")
    $lines.Add("- Peak memory: $peakPath")
    $lines.Add("- Load aggregate: $loadPath")
    $lines.Add("- All memory rows: $allMemoryPath")
    $lines.Add("- All load rows: $allLoadPath")
    $lines.Add("")
    $lines.Add("## Production Interpretation")
    $lines.Add("")
    $lines.Add("- If heap is small but residual anon is high, attack JVM/native/runtime retention, thread pools, allocator trim, or classpath surface before heap flags.")
    $lines.Add("- If Java-heavy endpoints dominate p99 and anon during load, move those routes to producer/direct/native serialization. Route admission alone cannot remove object graph allocation.")
    $lines.Add("- If conservative trim wins memory but p99 or 503 regresses, keep it opt-in for low-traffic idle pods.")
    $lines.Add("- `micro-dubbo` in this minimal gate uses static discovery to isolate Dubbo-on runtime surface without requiring external ZooKeeper. Run the sample consumer gate separately for real ZooKeeper discovery overhead.")
    $lines | Set-Content -Path $reportPath -Encoding UTF8
    return $reportPath
}

$script:RunRows = New-Object 'System.Collections.Generic.List[object]'
$script:MemoryRows = New-Object 'System.Collections.Generic.List[object]'
$script:LoadRows = New-Object 'System.Collections.Generic.List[object]'
$hasBuiltImage = [bool] $SkipInitialBuild

if (-not $SkipProfileRuns) {
    foreach ($profile in $Profiles) {
        Invoke-SmapsRun `
            -CaseName "profile" `
            -RuntimeProfile $profile `
            -Variant "minimal-smaps" `
            -ExtraJavaOpts "" `
            -RunFinalIdleSeconds $FinalIdleSeconds `
            -SkipBuild:$hasBuiltImage | Out-Null
        $hasBuiltImage = $true
    }
}

if (-not $SkipTrimAb) {
    Invoke-SmapsRun `
        -CaseName "trim-ab" `
        -RuntimeProfile "micro-rest" `
        -Variant "trim-off" `
        -ExtraJavaOpts $TrimOffJavaOpts `
        -RunFinalIdleSeconds $TrimFinalIdleSeconds `
        -RunFinalIdleSnapshotSeconds $TrimFinalIdleSnapshotSeconds `
        -SkipBuild:$hasBuiltImage | Out-Null
    $hasBuiltImage = $true

    Invoke-SmapsRun `
        -CaseName "trim-ab" `
        -RuntimeProfile "micro-rest" `
        -Variant "trim-on-conservative" `
        -ExtraJavaOpts $TrimOnJavaOpts `
        -RunFinalIdleSeconds $TrimFinalIdleSeconds `
        -RunFinalIdleSnapshotSeconds $TrimFinalIdleSnapshotSeconds `
        -SkipBuild:$hasBuiltImage | Out-Null
    $hasBuiltImage = $true
}

if (-not $SkipJavacore) {
    Invoke-SmapsRun `
        -CaseName "javacore" `
        -RuntimeProfile "micro-rest" `
        -Variant "native-memory-evidence" `
        -ExtraJavaOpts "" `
        -RunFinalIdleSeconds $JavacoreFinalIdleSeconds `
        -CollectJavacore `
        -SkipBuild:$hasBuiltImage | Out-Null
    $hasBuiltImage = $true
}

$memoryAggregate = Build-MemoryAggregate
$peakAggregate = Build-PeakAggregate
$loadAggregate = Build-LoadAggregate
$report = Write-GateReport -MemoryAggregate $memoryAggregate -PeakAggregate $peakAggregate -LoadAggregate $loadAggregate

Write-Host "anon evidence gate report: $report"
