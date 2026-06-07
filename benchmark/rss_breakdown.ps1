param(
    [int] $Port = 18182,
    [string[]] $ConcurrencyValues = @("64", "256"),
    [int] $DurationSeconds = 8,
    [int] $IdleSeconds = 10,
    [string[]] $EndpointSpecs = @(
        "small-direct|GET|/api/v1/candidates/direct|",
        "producer-heavy|GET|/api/v1/heavy/producer?items=100|",
        "dynamic-producer|GET|/api/v1/heavy/dto?items=100|",
        "raw-heavy|GET|/api/v1/heavy/raw|"
    ),
    [string] $RuntimeProfile = "micro-rest",
    [string] $ResultsDir = "",
    [switch] $SkipBuild,
    [switch] $KeepProcess
)

$ErrorActionPreference = "Stop"
if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$FrameworkRoot = Resolve-Path (Join-Path $ScriptDir "..")
$LoadRunner = Join-Path $ScriptDir "dubbo_overhead\load_runner.js"

$ConcurrencyValues = @(
    $ConcurrencyValues |
        ForEach-Object { "$_" -split "," } |
        ForEach-Object { $_.Trim() } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        ForEach-Object { [int] $_ }
)
if ($ConcurrencyValues.Count -eq 0) {
    throw "At least one concurrency value is required"
}
if (-not (Test-Path $LoadRunner)) {
    throw "Load runner not found: $LoadRunner"
}

if ([string]::IsNullOrWhiteSpace($ResultsDir)) {
    $ResultsDir = Join-Path $ScriptDir ("results\rss_breakdown_{0}" -f (Get-Date -Format "yyyyMMdd_HHmmss"))
}
$ResultsDir = [System.IO.Path]::GetFullPath($ResultsDir)
New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null

function Convert-BytesToMiB {
    param($Bytes)
    if ($null -eq $Bytes) {
        return 0.0
    }
    return [Math]::Round(([double] $Bytes) / 1048576.0, 3)
}

function Convert-KbToMiB {
    param($Kb)
    if ($null -eq $Kb) {
        return 0.0
    }
    return [Math]::Round(([double] $Kb) / 1024.0, 3)
}

function Get-Prop {
    param($Object, [string] $Name, $Default = 0)
    if ($null -eq $Object) {
        return $Default
    }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) {
        return $Default
    }
    return $property.Value
}

function Get-BufferPoolBytes {
    param($Diagnostic, [string] $Name)
    $pool = @($Diagnostic.buffer_pools | Where-Object { $_.name -eq $Name } | Select-Object -First 1)
    if ($pool.Count -eq 0) {
        return 0
    }
    return [int64] $pool[0].memory_used_bytes
}

function Get-RustAccountedBytes {
    param($Diagnostic)
    $native = $Diagnostic.native
    if ($null -eq $native) {
        return 0
    }
    $staticInline = [int64] (Get-Prop (Get-Prop $native "static_responses" $null) "file_inline_bytes" 0)
    $responseCache = [int64] (Get-Prop (Get-Prop $native "response_cache" $null) "bytes" 0)
    $bodyInFlight = [int64] (Get-Prop (Get-Prop $native "limiters" $null) "body_bytes_used" 0)
    $responseInFlight = [int64] (Get-Prop (Get-Prop $native "limiters" $null) "response_bytes_used" 0)
    return $staticInline + $responseCache + $bodyInFlight + $responseInFlight
}

function Get-RustAccountedText {
    param($Diagnostic)
    $native = $Diagnostic.native
    if ($null -eq $native) {
        return "native diagnostics unavailable"
    }
    $staticInline = Convert-BytesToMiB (Get-Prop (Get-Prop $native "static_responses" $null) "file_inline_bytes" 0)
    $responseCache = Convert-BytesToMiB (Get-Prop (Get-Prop $native "response_cache" $null) "bytes" 0)
    $bodyInFlight = Convert-BytesToMiB (Get-Prop (Get-Prop $native "limiters" $null) "body_bytes_used" 0)
    $responseInFlight = Convert-BytesToMiB (Get-Prop (Get-Prop $native "limiters" $null) "response_bytes_used" 0)
    $smallPool = Get-Prop (Get-Prop (Get-Prop $native "response_pool" $null) "small" $null) "len" 0
    $mediumPool = Get-Prop (Get-Prop (Get-Prop $native "response_pool" $null) "medium" $null) "len" 0
    $largePool = Get-Prop (Get-Prop (Get-Prop $native "response_pool" $null) "large" $null) "len" 0
    return "static_inline=${staticInline}MiB; cache=${responseCache}MiB; body_inflight=${bodyInFlight}MiB; response_inflight=${responseInFlight}MiB; response_pool_len small/medium/large=$smallPool/$mediumPool/$largePool"
}

function New-BreakdownRow {
    param(
        [string] $Phase,
        $Process,
        $Diagnostic
    )

    $heapUsed = [int64] $Diagnostic.jvm.heap_used_bytes
    $heapCommitted = [int64] $Diagnostic.jvm.heap_committed_bytes
    $heapMax = [int64] $Diagnostic.jvm.heap_max_bytes
    $nonHeapUsed = [int64] $Diagnostic.jvm.non_heap_used_bytes
    $nonHeapCommitted = [int64] $Diagnostic.jvm.non_heap_committed_bytes
    $direct = [int64] (Get-BufferPoolBytes $Diagnostic "direct")
    $mapped = [int64] (Get-BufferPoolBytes $Diagnostic "mapped")
    $rustAccounted = [int64] (Get-RustAccountedBytes $Diagnostic)
    $workingSet = [int64] $Process.WorkingSet64
    $knownUsed = $heapUsed + $nonHeapUsed + $direct + $mapped + $rustAccounted
    $residual = [Math]::Max(0, $workingSet - $knownUsed)

    [PSCustomObject]@{
        phase = $Phase
        pid = $Process.Id
        working_set_mib = Convert-BytesToMiB $workingSet
        private_commit_mib = Convert-BytesToMiB $Process.PrivateMemorySize64
        heap_used_mib = Convert-BytesToMiB $heapUsed
        heap_committed_mib = Convert-BytesToMiB $heapCommitted
        heap_max_mib = Convert-BytesToMiB $heapMax
        non_heap_used_mib = Convert-BytesToMiB $nonHeapUsed
        non_heap_committed_mib = Convert-BytesToMiB $nonHeapCommitted
        direct_buffer_mib = Convert-BytesToMiB $direct
        mapped_buffer_mib = Convert-BytesToMiB $mapped
        rust_accounted_mib = Convert-BytesToMiB $rustAccounted
        residual_native_jvm_os_mib = Convert-BytesToMiB $residual
        jvm_threads = [int] $Diagnostic.jvm.thread_count
        os_threads = [int] $Process.Threads.Count
        loaded_classes = [int] $Diagnostic.jvm.loaded_class_count
        rust_jni_workers = Get-Prop (Get-Prop $Diagnostic.native "jni" $null) "workers" 0
        rust_connections_used = Get-Prop (Get-Prop $Diagnostic.native "limiters" $null) "connections_used" 0
        rust_connection_limit = Get-Prop (Get-Prop $Diagnostic.native "limiters" $null) "connections_limit" 0
        rust_accounted_detail = Get-RustAccountedText $Diagnostic
    }
}

function Invoke-JsonEndpoint {
    param([string] $Path)
    $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port$Path" -TimeoutSec 10
    return $response.Content | ConvertFrom-Json
}

function Wait-Ready {
    for ($i = 0; $i -lt 80; $i++) {
        try {
            Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port/diagnostics/memory" -TimeoutSec 2 | Out-Null
            return
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    throw "Application did not become ready on port $Port"
}

function Save-Phase {
    param([string] $Phase, [System.Diagnostics.Process] $Process)
    $rawPath = Join-Path $ResultsDir ("{0}.diagnostics.json" -f $Phase)
    $content = (Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port/diagnostics/memory" -TimeoutSec 10).Content
    $content | Set-Content -Path $rawPath -Encoding UTF8
    $diagnostic = $content | ConvertFrom-Json
    return New-BreakdownRow -Phase $Phase -Process (Get-Process -Id $Process.Id) -Diagnostic $diagnostic
}

function Invoke-Load {
    param(
        [string] $Name,
        [string] $Method,
        [string] $Path,
        [string] $Body,
        [int] $Concurrency
    )
    $arguments = @(
        $LoadRunner,
        "--url", "http://127.0.0.1:$Port$Path",
        "--method", $Method,
        "--concurrency", "$Concurrency",
        "--duration-sec", "$DurationSeconds",
        "--timeout-ms", "10000"
    )
    if (-not [string]::IsNullOrEmpty($Body)) {
        $arguments += @("--body", $Body)
    }
    $raw = & node @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "load runner failed for $Name c$Concurrency"
    }
    $safe = $Name -replace '[^a-zA-Z0-9_-]+', '_'
    $out = Join-Path $ResultsDir ("load_{0}_c{1}.json" -f $safe, $Concurrency)
    $raw | Set-Content -Path $out -Encoding UTF8
    $load = $raw | ConvertFrom-Json
    [PSCustomObject]@{
        endpoint = $Name
        method = $Method
        path = $Path
        concurrency = $Concurrency
        requests = $load.requests
        errors_total = $load.errors_total
        statuses = ($load.statuses | ConvertTo-Json -Compress)
        rps = [Math]::Round($load.rps, 2)
        avg_ms = [Math]::Round($load.latency_us.avg / 1000.0, 3)
        p95_ms = [Math]::Round($load.latency_us.p95 / 1000.0, 3)
        p99_ms = [Math]::Round($load.latency_us.p99 / 1000.0, 3)
    }
}

function Get-ModuleRows {
    param([System.Diagnostics.Process] $Process)
    try {
        $fresh = Get-Process -Id $Process.Id
        return @(
            foreach ($module in $fresh.Modules) {
                $name = $module.ModuleName
                $path = $module.FileName
                $category = "other"
                if ($name -match "rust|hyper") {
                    $category = "rust-native"
                } elseif ($name -match "j9|jvm|java|jit|gc|omr|compressedrefs") {
                    $category = "openj9-jvm"
                } elseif ($path -match "\\Windows\\") {
                    $category = "windows-os"
                }
                [PSCustomObject]@{
                    module = $name
                    category = $category
                    module_memory_mib = Convert-BytesToMiB $module.ModuleMemorySize
                    path = $path
                }
            }
        )
    } catch {
        return @([PSCustomObject]@{
            module = "module-snapshot-unavailable"
            category = "error"
            module_memory_mib = 0
            path = $_.Exception.Message
        })
    }
}

function Write-Report {
    param($Rows, $LoadRows, $ModuleRows)
    $csv = Join-Path $ResultsDir "rss_breakdown.csv"
    $Rows | Export-Csv -Path $csv -NoTypeInformation -Encoding UTF8
    $loadCsv = Join-Path $ResultsDir "load_results.csv"
    $LoadRows | Export-Csv -Path $loadCsv -NoTypeInformation -Encoding UTF8
    $moduleCsv = Join-Path $ResultsDir "process_modules.csv"
    $ModuleRows | Export-Csv -Path $moduleCsv -NoTypeInformation -Encoding UTF8

    $peak = $Rows | Sort-Object working_set_mib -Descending | Select-Object -First 1
    $baseline = $Rows | Where-Object { $_.phase -eq "00_baseline" } | Select-Object -First 1
    $final = $Rows | Where-Object { $_.phase -eq "99_final_idle" } | Select-Object -First 1

    $report = Join-Path $ResultsDir "rss_breakdown_report.md"
    $lines = New-Object 'System.Collections.Generic.List[string]'
    $jvmVersion = ((& cmd /c "java -version 2>&1" | Select-Object -First 1) -join "")
    $lines.Add("# RSS Breakdown Report")
    $lines.Add("")
    $lines.Add("- Date: $(Get-Date -Format o)")
    $lines.Add("- Runtime profile: $RuntimeProfile")
    $lines.Add("- JVM: $jvmVersion")
    $lines.Add("- Port: $Port")
    $lines.Add("- Duration per load phase: ${DurationSeconds}s")
    $lines.Add("- Results CSV: $csv")
    $lines.Add("- Load CSV: $loadCsv")
    $lines.Add("- Process modules CSV: $moduleCsv")
    $lines.Add("")
    $lines.Add("## Important Interpretation")
    $lines.Add("")
    $lines.Add("- Windows RSS is reported from Process.WorkingSet64.")
    $lines.Add("- OpenJ9 on this host does not expose HotSpot NMT (VM.native_memory), so JVM internals and Rust/Tokio allocator pages cannot be split perfectly by the OS.")
    $lines.Add("- rust_accounted_mib is only the memory explicitly reported by the framework native diagnostics: static inline bytes, native response cache bytes, and in-flight body/response bytes.")
    $lines.Add("- residual_native_jvm_os_mib is the remaining RSS after subtracting heap used, non-heap used, direct/mapped buffers, and known Rust-accounted bytes. It includes JVM internals, JIT/code cache, thread stacks, loaded DLL pages, Rust Hyper/Tokio runtime pages, allocator fragmentation, and OS bookkeeping.")
    $lines.Add("- process_modules.csv reports mapped module image sizes, not per-module RSS. Use it only to see loaded JVM/Rust/OS native image surface.")
    $lines.Add("")
    $lines.Add("## Top-Level Result")
    $lines.Add("")
    $lines.Add("| Metric | Value |")
    $lines.Add("|---|---:|")
    if ($baseline) {
        $lines.Add("| Baseline RSS MiB | $($baseline.working_set_mib) |")
    }
    if ($peak) {
        $lines.Add("| Peak RSS MiB | $($peak.working_set_mib) |")
        $lines.Add("| Peak phase | $($peak.phase) |")
    }
    if ($final) {
        $lines.Add("| Final idle RSS MiB | $($final.working_set_mib) |")
        if ($baseline) {
            $lines.Add("| Final - baseline MiB | $([Math]::Round($final.working_set_mib - $baseline.working_set_mib, 3)) |")
        }
    }
    $lines.Add("")
    $lines.Add("## Hierarchical Breakdown By Phase")
    $lines.Add("")
    $lines.Add("| Phase | RSS MiB | Heap Used | Non-Heap Used | Direct | Rust Accounted | Residual Native/JVM/OS | JVM Threads | OS Threads | Classes |")
    $lines.Add("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    foreach ($row in $Rows) {
        $lines.Add("| $($row.phase) | $($row.working_set_mib) | $($row.heap_used_mib) | $($row.non_heap_used_mib) | $($row.direct_buffer_mib) | $($row.rust_accounted_mib) | $($row.residual_native_jvm_os_mib) | $($row.jvm_threads) | $($row.os_threads) | $($row.loaded_classes) |")
    }
    $lines.Add("")
    $lines.Add("## Rust-Native Accounted Detail")
    $lines.Add("")
    $lines.Add("| Phase | Detail |")
    $lines.Add("|---|---|")
    foreach ($row in $Rows) {
        $lines.Add("| $($row.phase) | $($row.rust_accounted_detail) |")
    }
    $lines.Add("")
    $lines.Add("## Native Module Surface")
    $lines.Add("")
    $lines.Add("| Category | Module Count | Total Module Image MiB |")
    $lines.Add("|---|---:|---:|")
    foreach ($group in ($ModuleRows | Group-Object category | Sort-Object Name)) {
        $total = [Math]::Round((($group.Group | Measure-Object module_memory_mib -Sum).Sum), 3)
        $lines.Add("| $($group.Name) | $($group.Count) | $total |")
    }
    $lines.Add("")
    $lines.Add("| Module | Category | Module Image MiB |")
    $lines.Add("|---|---|---:|")
    foreach ($module in ($ModuleRows | Sort-Object module_memory_mib -Descending | Select-Object -First 25)) {
        $lines.Add("| $($module.module) | $($module.category) | $($module.module_memory_mib) |")
    }
    $lines.Add("")
    $lines.Add("## Load Results")
    $lines.Add("")
    $lines.Add("| Endpoint | c | Requests | RPS | Avg ms | P95 ms | P99 ms | Errors | Statuses |")
    $lines.Add("|---|---:|---:|---:|---:|---:|---:|---:|---|")
    foreach ($row in $LoadRows) {
        $lines.Add("| $($row.endpoint) | $($row.concurrency) | $($row.requests) | $($row.rps) | $($row.avg_ms) | $($row.p95_ms) | $($row.p99_ms) | $($row.errors_total) | $($row.statuses) |")
    }
    $lines | Set-Content -Path $report -Encoding UTF8
    return $report
}

if (-not $SkipBuild) {
    Push-Location $FrameworkRoot
    try {
        & mvn -q -DskipTests package
        if ($LASTEXITCODE -ne 0) {
            throw "mvn package failed"
        }
    } finally {
        Pop-Location
    }
}

$classpath = "target/classes;target/dependency/*"
$javaArgs = @(
    "@src/main/resources/startup/openj9-micro-rss.options",
    "-Dserver.port=$Port",
    "-Dreactor.runtime.profile=$RuntimeProfile",
    "-Dreactor.rust.log.level=error",
    "-Dreactor.rust.java.log.level=warn",
    "-cp", $classpath,
    "com.reactor.rust.example.ReactorRustHyperApplication"
)

$stdout = Join-Path $ResultsDir "app.stdout.log"
$stderr = Join-Path $ResultsDir "app.stderr.log"
$app = $null
$Rows = New-Object 'System.Collections.Generic.List[object]'
$LoadRows = New-Object 'System.Collections.Generic.List[object]'

try {
    Push-Location $FrameworkRoot
    $app = Start-Process -FilePath "java" -ArgumentList $javaArgs -WorkingDirectory $FrameworkRoot -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    Pop-Location

    Wait-Ready
    $Rows.Add((Save-Phase -Phase "00_baseline" -Process $app))

    Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port/metrics/reset" -TimeoutSec 10 | Out-Null
    foreach ($spec in $EndpointSpecs) {
        $parts = $spec -split '\|', 4
        $name = $parts[0]
        $method = $parts[1]
        $path = $parts[2]
        $body = if ($parts.Count -gt 3) { $parts[3] } else { "" }
        Invoke-Load -Name "${name}_warmup" -Method $method -Path $path -Body $body -Concurrency 1 | Out-Null
    }
    $Rows.Add((Save-Phase -Phase "01_warmup" -Process $app))

    foreach ($concurrency in $ConcurrencyValues) {
        foreach ($spec in $EndpointSpecs) {
            $parts = $spec -split '\|', 4
            $name = $parts[0]
            $method = $parts[1]
            $path = $parts[2]
            $body = if ($parts.Count -gt 3) { $parts[3] } else { "" }
            $load = Invoke-Load -Name $name -Method $method -Path $path -Body $body -Concurrency $concurrency
            $LoadRows.Add($load)
            $Rows.Add((Save-Phase -Phase ("after_{0}_c{1}" -f $name, $concurrency) -Process $app))
            Start-Sleep -Seconds $IdleSeconds
            $Rows.Add((Save-Phase -Phase ("idle_{0}_c{1}" -f $name, $concurrency) -Process $app))
        }
    }

    try {
        & jcmd $app.Id help 2>&1 | Set-Content -Path (Join-Path $ResultsDir "jcmd_help.txt") -Encoding UTF8
        & jcmd $app.Id GC.class_histogram 2>&1 | Set-Content -Path (Join-Path $ResultsDir "jcmd_class_histogram.txt") -Encoding UTF8
    } catch {
        "jcmd diagnostics failed: $($_.Exception.Message)" | Set-Content -Path (Join-Path $ResultsDir "jcmd_error.txt") -Encoding UTF8
    }

    $Rows.Add((Save-Phase -Phase "99_final_idle" -Process $app))
    $ModuleRows = Get-ModuleRows -Process $app
    $report = Write-Report -Rows $Rows -LoadRows $LoadRows -ModuleRows $ModuleRows
    Write-Output "rss breakdown report: $report"
} finally {
    if ($app -and -not $app.HasExited -and -not $KeepProcess) {
        Stop-Process -Id $app.Id -Force
    }
}
