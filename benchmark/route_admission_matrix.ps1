param(
    [string] $EndpointClass = "producer-json",
    [string] $BenchmarkEndpointClasses = "",
    [string] $RouteAdmissionKey = "get.api.v1.heavy.producer",
    [object] $ConcurrencyLevels = "256,512",
    [object] $MaxConcurrentValues = "64,80,96,128",
    [object] $QueueTimeoutMsValues = "75,125,150",
    [string] $Duration = "20s",
    [string] $Warmup = "5s",
    [int] $RepeatCount = 1,
    [string] $RuntimeProfile = "micro-rest-plus",
    [string] $FrameworkJvmPreset = "current",
    [int] $RandomSeed = 0,
    [string] $ResultsRoot = "",
    [switch] $PlanPreWarm,
    [string] $PlanPreWarmDuration = "3s",
    [switch] $FrameworkOnly,
    [switch] $SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
[System.Threading.Thread]::CurrentThread.CurrentCulture = [System.Globalization.CultureInfo]::InvariantCulture
[System.Threading.Thread]::CurrentThread.CurrentUICulture = [System.Globalization.CultureInfo]::InvariantCulture

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $ScriptDir

function ConvertTo-IntList {
    param([object] $Value)

    $items = @()
    if ($Value -is [array]) {
        foreach ($entry in $Value) {
            $items += ConvertTo-IntList -Value $entry
        }
        return @($items)
    }

    return @(
        ("$Value" -split "[,\s]+") |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            ForEach-Object { [int] $_.Trim() }
    )
}

function ConvertTo-Seconds {
    param([string] $Value)

    $trimmed = $Value.Trim().ToLowerInvariant()
    if ($trimmed.EndsWith("ms")) {
        return ([double] $trimmed.Substring(0, $trimmed.Length - 2)) / 1000.0
    }
    if ($trimmed.EndsWith("s")) {
        return [double] $trimmed.Substring(0, $trimmed.Length - 1)
    }
    return [double] $trimmed
}

function Parse-StatusCounts {
    param([string] $StatusText)

    $counts = @{}
    if ([string]::IsNullOrWhiteSpace($StatusText)) {
        return $counts
    }

    foreach ($part in ($StatusText -split ",")) {
        $kv = $part.Trim() -split "=", 2
        if ($kv.Length -eq 2) {
            $counts[$kv[0].Trim()] = [int] $kv[1].Trim()
        }
    }
    return $counts
}

function Parse-LatencyMs {
    param([string] $Value)

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return 0.0
    }

    $trimmed = $Value.Trim().ToLowerInvariant()
    if ($trimmed.EndsWith("ms")) {
        return [double] $trimmed.Substring(0, $trimmed.Length - 2)
    }
    if ($trimmed.EndsWith("us")) {
        return ([double] $trimmed.Substring(0, $trimmed.Length - 2)) / 1000.0
    }
    if ($trimmed.EndsWith("s")) {
        return ([double] $trimmed.Substring(0, $trimmed.Length - 1)) * 1000.0
    }
    return [double] $trimmed
}

function Get-PromMetric {
    param(
        [string] $FilePath,
        [string] $MetricName
    )

    if (-not (Test-Path $FilePath)) {
        return 0
    }

    $line = Get-Content -Path $FilePath |
        Where-Object { $_ -match "^$([regex]::Escape($MetricName))(\{| )" } |
        Select-Object -First 1

    if ([string]::IsNullOrWhiteSpace($line)) {
        return 0
    }

    $parts = $line.Trim() -split "\s+"
    if ($parts.Length -lt 2) {
        return 0
    }
    return [double] $parts[$parts.Length - 1]
}

function Get-ProbeElapsedSeconds {
    param([string] $FilePath)

    if (-not (Test-Path $FilePath)) {
        return 0.0
    }

    $line = Get-Content -Path $FilePath |
        Where-Object { $_ -match "^\s*elapsed:\s*" } |
        Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace($line)) {
        return 0.0
    }

    $value = ($line -replace "^\s*elapsed:\s*", "").Trim()
    return ConvertTo-Seconds -Value $value
}

function ConvertTo-SafeEndpoint {
    param([string] $Endpoint)

    return ($Endpoint -replace "[^A-Za-z0-9_-]", "_")
}

function Read-BenchmarkRows {
    param(
        [string] $RunDir,
        [int] $MaxConcurrent,
        [int] $QueueTimeoutMs
    )

    $summary = Join-Path $RunDir "summary.md"
    if (-not (Test-Path $summary)) {
        throw "Missing summary: $summary"
    }

    $rows = New-Object System.Collections.Generic.List[object]
    foreach ($line in Get-Content -Path $summary) {
        if ($line -notmatch "^\|\s*\d+\s*\|\s*rust_java\s*\|") {
            continue
        }

        $cols = @($line -split "\|" | ForEach-Object { $_.Trim() })
        if ($cols.Length -gt 0 -and $cols[0] -eq "") {
            $cols = @($cols[1..($cols.Length - 1)])
        }
        if ($cols.Length -gt 0 -and $cols[$cols.Length - 1] -eq "") {
            $cols = @($cols[0..($cols.Length - 2)])
        }
        if ($cols.Length -lt 16) {
            continue
        }

        $runId = [int] $cols[0]
        $class = $cols[2]
        $endpoint = $cols[3]
        $method = $cols[4]
        $concurrency = [int] $cols[5]
        $rps = [double] $cols[6]
        $p99Ms = Parse-LatencyMs -Value $cols[10]
        $statusCounts = Parse-StatusCounts -StatusText $cols[11]
        $status200 = if ($statusCounts.ContainsKey("200")) { $statusCounts["200"] } else { 0 }
        $status503 = if ($statusCounts.ContainsKey("503")) { $statusCounts["503"] } else { 0 }
        $totalStatuses = 0
        foreach ($count in $statusCounts.Values) {
            $totalStatuses += [int] $count
        }

        $safeEndpoint = ConvertTo-SafeEndpoint -Endpoint $endpoint
        $prefix = "rust_java_${safeEndpoint}_${method}_c${concurrency}_r${runId}"
        $probeFile = Join-Path $RunDir "$prefix.txt"
        $metricsFile = Join-Path $RunDir "$prefix.metrics.prom"
        $elapsed = Get-ProbeElapsedSeconds -FilePath $probeFile
        $useful200Rps = if ($elapsed -gt 0) { [Math]::Round($status200 / $elapsed, 2) } else { 0.0 }
        $rejectPct = if ($totalStatuses -gt 0) { [Math]::Round(($status503 * 100.0) / $totalStatuses, 2) } else { 0.0 }

        $rows.Add([PSCustomObject]@{
            MaxConcurrent = $MaxConcurrent
            QueueTimeoutMs = $QueueTimeoutMs
            Run = $runId
            Class = $class
            Endpoint = $endpoint
            Method = $method
            C = $concurrency
            RPS = $rps
            Useful200RPS = $useful200Rps
            P99Ms = [Math]::Round($p99Ms, 2)
            Status200 = $status200
            Status503 = $status503
            Status503Pct = $rejectPct
            RSSAfterMiB = [double] $cols[14]
            MaxContainerMemMiB = [double] $cols[15]
            RouteAccepted = [int] (Get-PromMetric -FilePath $metricsFile -MetricName "reactor_native_route_admission_accepted_total")
            RouteRejected = [int] (Get-PromMetric -FilePath $metricsFile -MetricName "reactor_native_route_admission_rejected_total")
            RouteTimeout = [int] (Get-PromMetric -FilePath $metricsFile -MetricName "reactor_native_route_admission_timeout_total")
            JniQueueFull = [int] (Get-PromMetric -FilePath $metricsFile -MetricName "reactor_native_jni_queue_full_total")
            JniQueueP99Us = [int] (Get-PromMetric -FilePath $metricsFile -MetricName "reactor_native_jni_queue_duration_p99_us")
            ResultDir = $RunDir
        })
    }

    return $rows.ToArray()
}

function Write-MatrixMarkdown {
    param(
        [object[]] $Rows,
        [object[]] $AggregateRows,
        [string] $FilePath
    )

    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("# Route Admission Matrix")
    $lines.Add("")
    $lines.Add(("- Tuned endpoint class: ``{0}``" -f $EndpointClass))
    $lines.Add(("- Benchmark endpoint classes: ``{0}``" -f $EffectiveEndpointClasses))
    $lines.Add(("- Route admission key: ``{0}``" -f $RouteAdmissionKey))
    $lines.Add(("- Runtime profile: ``{0}``" -f $RuntimeProfile))
    $lines.Add(("- JVM preset: ``{0}``" -f $FrameworkJvmPreset))
    $lines.Add(("- Framework only: ``{0}``" -f $FrameworkOnly.IsPresent))
    $lines.Add(("- Duration: ``{0}``, warmup: ``{1}``, repeat: ``{2}``" -f $Duration, $Warmup, $RepeatCount))
    $lines.Add(("- Plan pre-warm: ``{0}``, duration: ``{1}``" -f $PlanPreWarm.IsPresent, $PlanPreWarmDuration))
    $lines.Add("")
    $lines.Add("## Aggregate")
    $lines.Add("")
    $lines.Add("| maxConcurrent | queueTimeoutMs | Class | C | Runs | Avg Useful 200 RPS | Min Useful 200 RPS | Max Useful 200 RPS | Avg P99 ms | Max P99 ms | Avg 503 % | Avg RSS MiB | Max RSS MiB | Avg Max Mem MiB | Avg Route Rejected | Avg JNI P99 us |")
    $lines.Add("|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")

    foreach ($row in ($AggregateRows | Sort-Object MaxConcurrent, QueueTimeoutMs, Class, C)) {
        $lines.Add((
            "| {0} | {1} | {2} | {3} | {4} | {5:N2} | {6:N2} | {7:N2} | {8:N2} | {9:N2} | {10:N2} | {11:N2} | {12:N2} | {13:N2} | {14:N0} | {15:N0} |" -f
            $row.MaxConcurrent,
            $row.QueueTimeoutMs,
            $row.Class,
            $row.C,
            $row.Runs,
            $row.AvgUseful200RPS,
            $row.MinUseful200RPS,
            $row.MaxUseful200RPS,
            $row.AvgP99Ms,
            $row.MaxP99Ms,
            $row.Avg503Pct,
            $row.AvgRSSAfterMiB,
            $row.MaxRSSAfterMiB,
            $row.AvgMaxContainerMemMiB,
            $row.AvgRouteRejected,
            $row.AvgJniQueueP99Us
        ))
    }

    $lines.Add("")
    $lines.Add("## Runs")
    $lines.Add("")
    $lines.Add("| maxConcurrent | queueTimeoutMs | Run | Class | Endpoint | C | RPS | Useful 200 RPS | P99 ms | 503 % | RSS MiB | Max Mem MiB | Route Rejected | JNI Queue Full | JNI P99 us |")
    $lines.Add("|---:|---:|---:|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")

    foreach ($row in ($Rows | Sort-Object MaxConcurrent, QueueTimeoutMs, C, Run)) {
        $lines.Add((
            "| {0} | {1} | {2} | {3} | {4} | {5} | {6:N2} | {7:N2} | {8:N2} | {9:N2} | {10:N2} | {11:N2} | {12} | {13} | {14} |" -f
            $row.MaxConcurrent,
            $row.QueueTimeoutMs,
            $row.Run,
            $row.Class,
            $row.Endpoint,
            $row.C,
            $row.RPS,
            $row.Useful200RPS,
            $row.P99Ms,
            $row.Status503Pct,
            $row.RSSAfterMiB,
            $row.MaxContainerMemMiB,
            $row.RouteRejected,
            $row.JniQueueFull,
            $row.JniQueueP99Us
        ))
    }

    $lines | Set-Content -Path $FilePath -Encoding UTF8
}

function Get-MatrixAggregateRows {
    param([object[]] $Rows)

    return @(
        $Rows |
            Group-Object MaxConcurrent, QueueTimeoutMs, Class, C |
            ForEach-Object {
                $group = @($_.Group)
                [PSCustomObject]@{
                    MaxConcurrent = [int] $group[0].MaxConcurrent
                    QueueTimeoutMs = [int] $group[0].QueueTimeoutMs
                    Class = [string] $group[0].Class
                    C = [int] $group[0].C
                    Runs = $group.Count
                    AvgUseful200RPS = [Math]::Round(($group | Measure-Object -Property Useful200RPS -Average).Average, 2)
                    MinUseful200RPS = [Math]::Round(($group | Measure-Object -Property Useful200RPS -Minimum).Minimum, 2)
                    MaxUseful200RPS = [Math]::Round(($group | Measure-Object -Property Useful200RPS -Maximum).Maximum, 2)
                    AvgP99Ms = [Math]::Round(($group | Measure-Object -Property P99Ms -Average).Average, 2)
                    MaxP99Ms = [Math]::Round(($group | Measure-Object -Property P99Ms -Maximum).Maximum, 2)
                    Avg503Pct = [Math]::Round(($group | Measure-Object -Property Status503Pct -Average).Average, 2)
                    AvgRSSAfterMiB = [Math]::Round(($group | Measure-Object -Property RSSAfterMiB -Average).Average, 2)
                    MaxRSSAfterMiB = [Math]::Round(($group | Measure-Object -Property RSSAfterMiB -Maximum).Maximum, 2)
                    AvgMaxContainerMemMiB = [Math]::Round(($group | Measure-Object -Property MaxContainerMemMiB -Average).Average, 2)
                    MaxContainerMemMiB = [Math]::Round(($group | Measure-Object -Property MaxContainerMemMiB -Maximum).Maximum, 2)
                    AvgRouteRejected = [Math]::Round(($group | Measure-Object -Property RouteRejected -Average).Average, 0)
                    AvgJniQueueP99Us = [Math]::Round(($group | Measure-Object -Property JniQueueP99Us -Average).Average, 0)
                }
            }
    )
}

$concurrencyValues = ConvertTo-IntList -Value $ConcurrencyLevels
$maxConcurrentList = ConvertTo-IntList -Value $MaxConcurrentValues
$queueTimeoutList = ConvertTo-IntList -Value $QueueTimeoutMsValues
$EffectiveEndpointClasses = if ([string]::IsNullOrWhiteSpace($BenchmarkEndpointClasses)) {
    $EndpointClass
} else {
    $BenchmarkEndpointClasses
}

if ([string]::IsNullOrWhiteSpace($ResultsRoot)) {
    $timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
    $ResultsRoot = Join-Path $ScriptDir "results\route_admission_matrix_$timestamp"
}
$ResultsRoot = [System.IO.Path]::GetFullPath($ResultsRoot)
New-Item -ItemType Directory -Force -Path $ResultsRoot | Out-Null

$containerBenchmark = Join-Path $ScriptDir "container_benchmark.ps1"
if (-not (Test-Path $containerBenchmark)) {
    throw "Missing container benchmark script: $containerBenchmark"
}

$allRows = New-Object System.Collections.Generic.List[object]
$comboIndex = 0
foreach ($maxConcurrent in $maxConcurrentList) {
    foreach ($queueTimeoutMs in $queueTimeoutList) {
        $comboIndex++
        $seed = if ($RandomSeed -gt 0) { $RandomSeed + $comboIndex } else { 0 }
        $runDir = Join-Path $ResultsRoot ("mc{0}_qt{1}" -f $maxConcurrent, $queueTimeoutMs)
        $javaOpts = "-Dreactor.rust.route-admission.$RouteAdmissionKey.max-concurrent=$maxConcurrent -Dreactor.rust.route-admission.$RouteAdmissionKey.queue-timeout-ms=$queueTimeoutMs"

        Write-Host "Running route admission combo: maxConcurrent=$maxConcurrent queueTimeoutMs=$queueTimeoutMs results=$runDir"

        $arguments = @(
            "-ExecutionPolicy", "Bypass",
            "-File", $containerBenchmark,
            "-RuntimeProfile", $RuntimeProfile,
            "-FrameworkJvmPreset", $FrameworkJvmPreset,
            "-EndpointClasses", $EffectiveEndpointClasses,
            "-ConcurrencyLevels", ($concurrencyValues -join ","),
            "-Duration", $Duration,
            "-Warmup", $Warmup,
            "-RepeatCount", "$RepeatCount",
            "-ResultsDir", $runDir,
            "-FrameworkJavaOptsAppend", $javaOpts
        )
        if ($seed -gt 0) {
            $arguments += @("-RandomSeed", "$seed")
        }
        if ($PlanPreWarm) {
            $arguments += @("-PlanPreWarm", "-PlanPreWarmDuration", $PlanPreWarmDuration)
        }
        if ($FrameworkOnly) {
            $arguments += "-FrameworkOnly"
        }
        if ($SkipBuild) {
            $arguments += "-SkipBuild"
        }

        & powershell @arguments
        if ($LASTEXITCODE -ne 0) {
            throw "container_benchmark.ps1 failed for maxConcurrent=$maxConcurrent queueTimeoutMs=$queueTimeoutMs"
        }

        $rows = Read-BenchmarkRows -RunDir $runDir -MaxConcurrent $maxConcurrent -QueueTimeoutMs $queueTimeoutMs
        foreach ($row in $rows) {
            $allRows.Add($row)
        }
    }
}

$csv = Join-Path $ResultsRoot "route_admission_matrix.csv"
$aggregateCsv = Join-Path $ResultsRoot "route_admission_matrix_aggregate.csv"
$md = Join-Path $ResultsRoot "route_admission_matrix.md"
$matrixRows = $allRows.ToArray()
$aggregateRows = Get-MatrixAggregateRows -Rows $matrixRows
$matrixRows | Export-Csv -Path $csv -NoTypeInformation -Encoding UTF8
$aggregateRows | Export-Csv -Path $aggregateCsv -NoTypeInformation -Encoding UTF8
Write-MatrixMarkdown -Rows $matrixRows -AggregateRows $aggregateRows -FilePath $md

Write-Host "Route admission matrix complete:"
Write-Host "  $csv"
Write-Host "  $aggregateCsv"
Write-Host "  $md"
