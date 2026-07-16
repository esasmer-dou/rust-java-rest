param(
    [string] $Image = "rust-java-rest:benchmark",
    [ValidateSet("micro-rest", "micro-rest-plus", "micro-dubbo", "micro-rest-cpu1", "micro-rest-cpu1-no-thp", "micro-rest-cpu1-4k", "micro-rss", "ultra-low-rss", "throughput")]
    [string] $RuntimeProfile = "micro-rest",
    [ValidateSet("sample", "minimal")]
    [string] $AppMode = "sample",
    [string[]] $ConcurrencyValues = @("64", "256"),
    [string[]] $EndpointSpecs = @(
        "small-direct|/api/v1/candidates/direct",
        "direct-heavy|/api/v1/heavy?items=100",
        "producer-heavy|/api/v1/heavy/producer?items=100",
        "dynamic-producer|/api/v1/heavy/dto?items=100",
        "raw-heavy|/api/v1/heavy/raw"
    ),
    [int] $DurationSeconds = 6,
    [int] $IdleSeconds = 5,
    [int] $FinalIdleSeconds = 10,
    [string[]] $FinalIdleSnapshotSeconds = @(),
    [int] $HostPort = 18186,
    [string] $ResultsDir = "",
    [double] $CodeCacheMaxRAMPercentage = 0,
    [string] $CodeCacheTotal = "",
    [ValidateSet("", "256k", "192k", "160k", "128k")]
    [string] $JvmXss = "",
    [string] $ExtraJavaOpts = "",
    [string] $JavaToolOptions = "",
    [switch] $TrimBeforeFinalIdle,
    [switch] $CollectJavacore,
    [switch] $SkipBuild,
    [switch] $KeepContainer
)

$ErrorActionPreference = "Stop"
if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$FrameworkRoot = Resolve-Path (Join-Path $ScriptDir "..")
$RunnerImage = "reactor-benchmark-runner:local"
$Container = "rust-java-linux-smaps-$AppMode"
$HostBaseUrl = "http://127.0.0.1:$HostPort"
$JavacoreEvidenceDir = ""

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

$EndpointSpecs = @(
    $EndpointSpecs |
        ForEach-Object { "$_" -split "," } |
        ForEach-Object { $_.Trim() } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
)
if ($EndpointSpecs.Count -eq 0) {
    throw "At least one endpoint spec is required."
}

$FinalIdleSnapshotValues = @(
    $FinalIdleSnapshotSeconds |
        ForEach-Object { "$_" -split "," } |
        ForEach-Object { $_.Trim() } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        ForEach-Object { [int] $_ } |
        Where-Object { $_ -gt 0 } |
        Sort-Object -Unique
)

if ([string]::IsNullOrWhiteSpace($ResultsDir)) {
    $ResultsDir = Join-Path $ScriptDir ("results\linux_smaps_{0}_{1}_{2}" -f $AppMode, $RuntimeProfile, (Get-Date -Format "yyyyMMdd_HHmmss"))
}
$ResultsDir = [System.IO.Path]::GetFullPath($ResultsDir)
New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null

function Convert-KbToMiB {
    param($Kb)
    if ($null -eq $Kb) {
        return 0.0
    }
    return [Math]::Round(([double] $Kb) / 1024.0, 3)
}

function Convert-BytesToMiB {
    param($Bytes)
    if ($null -eq $Bytes) {
        return 0.0
    }
    return [Math]::Round(([double] $Bytes) / 1048576.0, 3)
}

function Join-JavaOptions {
    param([string[]] $Parts)
    return (($Parts | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join " ")
}

function Set-JavaXssOption {
    param([string] $JavaOpts, [string] $Xss)
    if ([string]::IsNullOrWhiteSpace($Xss)) {
        return $JavaOpts
    }
    $parts = @(
        $JavaOpts -split "\s+" |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            Where-Object { $_ -notmatch "^-Xss" }
    )
    return Join-JavaOptions @($parts + @("-Xss$Xss"))
}

function Get-ProfileConfig {
    switch ($RuntimeProfile) {
        "micro-rss" {
            return [PSCustomObject]@{
                Memory = "80m"
                JavaOpts = Join-JavaOptions @(
                    "-Xms4m", "-Xmx24m", "-Xss256k", "-Xquickstart", "-Xtune:virtualized", "-Xshareclasses:none",
                    "-Xnojit", "-XX:ActiveProcessorCount=1", "-Xgc:threads=1", "-XX:-TransparentHugePage",
                    "-Dreactor.rust.jni.workers=1",
                    "-Dreactor.rust.jni.queue-capacity=128",
                    "-Dreactor.rust.http.max-connections=192",
                    "-Dreactor.rust.http.max-inflight-body-bytes=4194304",
                    "-Dreactor.rust.http.max-inflight-response-bytes=8388608",
                    "-Dreactor.rust.http.http1-only-enabled=true",
                    "-Dreactor.rust.runtime.worker-threads=1",
                    "-Dreactor.rust.runtime.max-blocking-threads=2",
                    "-Dreactor.rust.runtime.thread-stack-bytes=196608",
                    "-Dreactor.rust.file-stream.chunk-bytes=32768",
                    "-Dreactor.rust.static-file.inline-max-bytes=65536",
                    "-Dreactor.rust.static-file.max-concurrent-streams=32",
                    "-Dreactor.rust.response-pool.small-capacity=8",
                    "-Dreactor.rust.response-pool.medium-capacity=8",
                    "-Dreactor.rust.response-pool.large-capacity=1",
                    "-Dreactor.rust.response-pool.huge-capacity=1",
                    "-Dreactor.rust.native-cache.max-entries=64",
                    "-Dreactor.rust.native-cache.max-bytes=2097152",
                    "-Dreactor.rust.json.writer-retain-max-bytes=32768",
                    "-Dreactor.rust.log.level=error",
                    "-Dreactor.rust.java.log.level=warn",
                    "-Dfile.encoding=UTF-8",
                    "-Djava.security.egd=file:/dev/./urandom"
                )
            }
        }
        "micro-rest-cpu1" {
            return [PSCustomObject]@{
                Memory = "80m"
                JavaOpts = Join-JavaOptions @(
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
                )
            }
        }
        "micro-rest-cpu1-no-thp" {
            return [PSCustomObject]@{
                Memory = "80m"
                JavaOpts = Join-JavaOptions @(
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
                )
            }
        }
        "micro-rest-cpu1-4k" {
            return [PSCustomObject]@{
                Memory = "80m"
                JavaOpts = Join-JavaOptions @(
                    "-Xms8m",
                    "-Xmx40m",
                    "-Xss256k",
                    "-Xquickstart",
                    "-Xtune:virtualized",
                    "-Xshareclasses:none",
                    "-XX:ActiveProcessorCount=1",
                    "-Xgc:threads=1",
                    "-Xlp:objectheap:pagesize=4K",
                    "-Dreactor.runtime.profile=micro-rest",
                    "-Dreactor.rust.log.level=error",
                    "-Dreactor.rust.java.log.level=warn",
                    "-Dfile.encoding=UTF-8",
                    "-Djava.security.egd=file:/dev/./urandom"
                )
            }
        }
        "micro-rest-plus" {
            return [PSCustomObject]@{
                Memory = "80m"
                JavaOpts = Join-JavaOptions @(
                    "-Xms8m",
                    "-Xmx40m",
                    "-Xss256k",
                    "-Xquickstart",
                    "-Xtune:virtualized",
                    "-Xshareclasses:none",
                    "-XX:ActiveProcessorCount=1",
                    "-Xgc:threads=1",
                    "-XX:-TransparentHugePage",
                    "-Dreactor.runtime.profile=micro-rest-plus",
                    "-Dreactor.rust.log.level=error",
                    "-Dreactor.rust.java.log.level=warn",
                    "-Dfile.encoding=UTF-8",
                    "-Djava.security.egd=file:/dev/./urandom"
                )
            }
        }
        "micro-dubbo" {
            return [PSCustomObject]@{
                Memory = "96m"
                JavaOpts = Join-JavaOptions @(
                    "-Xms8m",
                    "-Xmx48m",
                    "-Xss256k",
                    "-Xquickstart",
                    "-Xtune:virtualized",
                    "-Xshareclasses:none",
                    "-XX:ActiveProcessorCount=1",
                    "-Xgc:threads=1",
                    "-XX:-TransparentHugePage",
                    "-Dreactor.runtime.profile=micro-dubbo",
                    "-Dreactor.dubbo.runtime.profile=micro-dubbo",
                    "-Dreactor.dubbo.enabled=true",
                    "-Dreactor.dubbo.discovery=static",
                    "-Dreactor.dubbo.providers=127.0.0.1:20880",
                    "-Dreactor.dubbo.registry-check=false",
                    "-Dreactor.dubbo.check=false",
                    "-Dreactor.rust.log.level=error",
                    "-Dreactor.rust.java.log.level=warn",
                    "-Dfile.encoding=UTF-8",
                    "-Djava.security.egd=file:/dev/./urandom"
                )
            }
        }
        "ultra-low-rss" {
            return [PSCustomObject]@{
                Memory = "80m"
                JavaOpts = Join-JavaOptions @(
                    "-Xms4m", "-Xmx32m", "-Xss256k", "-Xquickstart", "-Xtune:virtualized", "-Xshareclasses:none",
                    "-XX:ActiveProcessorCount=1", "-Xgc:threads=1", "-XX:-TransparentHugePage",
                    "-Dreactor.runtime.profile=micro-rest",
                    "-Dreactor.rust.jni.workers=1",
                    "-Dreactor.rust.jni.queue-capacity=96",
                    "-Dreactor.rust.http.max-connections=256",
                    "-Dreactor.rust.http.max-inflight-body-bytes=2097152",
                    "-Dreactor.rust.http.max-inflight-response-bytes=4194304",
                    "-Dreactor.rust.http.http1-only-enabled=true",
                    "-Dreactor.rust.runtime.worker-threads=1",
                    "-Dreactor.rust.runtime.max-blocking-threads=1",
                    "-Dreactor.rust.runtime.thread-stack-bytes=196608",
                    "-Dreactor.rust.file-stream.chunk-bytes=32768",
                    "-Dreactor.rust.static-file.inline-max-bytes=0",
                    "-Dreactor.rust.static-file.max-concurrent-streams=16",
                    "-Dreactor.rust.response-pool.small-capacity=4",
                    "-Dreactor.rust.response-pool.medium-capacity=1",
                    "-Dreactor.rust.response-pool.large-capacity=1",
                    "-Dreactor.rust.response-pool.huge-capacity=1",
                    "-Dreactor.rust.native-cache.max-entries=0",
                    "-Dreactor.rust.native-cache.max-bytes=0",
                    "-Dreactor.rust.json.writer-retain-max-bytes=16384",
                    "-Dreactor.rust.log.level=error",
                    "-Dreactor.rust.java.log.level=warn",
                    "-Dfile.encoding=UTF-8",
                    "-Djava.security.egd=file:/dev/./urandom"
                )
            }
        }
        "throughput" {
            return [PSCustomObject]@{
                Memory = "256m"
                JavaOpts = Join-JavaOptions @(
                    "-Xms32m", "-Xmx128m", "-Xquickstart", "-Xtune:virtualized", "-Xshareclasses:none",
                    "-Dreactor.rust.jni.workers=0",
                    "-Dreactor.rust.jni.queue-capacity=4096",
                    "-Dreactor.rust.http.max-connections=4096",
                    "-Dreactor.rust.http.max-inflight-body-bytes=134217728",
                    "-Dreactor.rust.http.max-inflight-response-bytes=268435456",
                    "-Dreactor.rust.file-stream.chunk-bytes=262144",
                    "-Dreactor.rust.static-file.inline-max-bytes=2097152",
                    "-Dreactor.rust.static-file.max-concurrent-streams=1024",
                    "-Dreactor.rust.response-pool.small-capacity=512",
                    "-Dreactor.rust.response-pool.medium-capacity=1024",
                    "-Dreactor.rust.response-pool.large-capacity=128",
                    "-Dreactor.rust.response-pool.huge-capacity=16",
                    "-Dreactor.rust.native-cache.max-entries=4096",
                    "-Dreactor.rust.native-cache.max-bytes=67108864",
                    "-Dfile.encoding=UTF-8",
                    "-Djava.security.egd=file:/dev/./urandom"
                )
            }
        }
        default {
            return [PSCustomObject]@{
                Memory = "80m"
                JavaOpts = Join-JavaOptions @(
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
                )
            }
        }
    }
}

function Find-FrameworkSampleJar {
    $jar = Get-ChildItem -Path (Join-Path $FrameworkRoot "sample\target") -Filter "rust-java-rest-*-sample.jar" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $jar) {
        throw "Framework executable sample jar not found. Build core, then run mvn package in sample/."
    }
    return "sample/target/$($jar.Name)"
}

function Find-FrameworkCoreRuntimeJar {
    $jar = Get-ChildItem -Path (Join-Path $FrameworkRoot "target") -Filter "rust-java-rest-*-core-runtime.jar" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $jar) {
        throw "Framework core-runtime jar not found. Run mvn package first."
    }
    return "target/$($jar.Name)"
}

function Remove-ContainerIfExists {
    param([string] $Name)
    $existing = @(& docker ps -a --format "{{.Names}}" | Where-Object { $_ -eq $Name })
    if ($existing.Count -gt 0) {
        & docker rm -f $Name | Out-Null
    }
}

function Wait-Ready {
    for ($i = 0; $i -lt 60; $i++) {
        try {
            Invoke-WebRequest -UseBasicParsing "$HostBaseUrl/health" -TimeoutSec 2 | Out-Null
            return
        } catch {
            Start-Sleep -Seconds 1
        }
    }
    docker logs $Container *> (Join-Path $ResultsDir "$Container.startup.log")
    throw "$Container did not become healthy on $HostBaseUrl."
}

function Invoke-ContainerText {
    param([string] $Command)
    return (& docker exec $Container sh -c $Command) -join "`n"
}

function Read-KvKb {
    param([string] $Text, [string] $Name)
    $match = [regex]::Match($Text, "(?m)^$([regex]::Escape($Name)):\s+([0-9]+)\s+kB")
    if ($match.Success) {
        return [int64] $match.Groups[1].Value
    }
    return 0
}

function Read-StatusKb {
    param([string] $Text, [string] $Name)
    return Read-KvKb -Text $Text -Name $Name
}

function Read-CgroupStatBytes {
    param([string] $Text, [string] $Name)
    $match = [regex]::Match($Text, "(?m)^$([regex]::Escape($Name))\s+([0-9]+)")
    if ($match.Success) {
        return [int64] $match.Groups[1].Value
    }
    return 0
}

function Get-BufferPoolBytes {
    param($Diagnostic, [string] $Name)
    $pool = @($Diagnostic.buffer_pools | Where-Object { $_.name -eq $Name } | Select-Object -First 1)
    if ($pool.Count -eq 0) {
        return 0
    }
    return [int64] $pool[0].memory_used_bytes
}

function Get-MemoryPoolCategory {
    param([string] $Name, [string] $Type)
    $lower = $Name.ToLowerInvariant()
    if ($Type -eq "HEAP") {
        return "heap"
    }
    if ($lower -match "jit|code") {
        return "jit-code"
    }
    if ($lower -match "class|rom") {
        return "class-metadata"
    }
    return "non-heap-other"
}

function Get-MemoryPoolBytesByCategory {
    param($Diagnostic, [string] $Category, [string] $Field)
    $sum = [int64] 0
    foreach ($pool in @($Diagnostic.memory_pools)) {
        $poolCategory = Get-MemoryPoolCategory -Name ([string] $pool.name) -Type ([string] $pool.type)
        if ($poolCategory -ne $Category) {
            continue
        }
        $value = [int64] $pool.$Field
        if ($value -gt 0) {
            $sum += $value
        }
    }
    return $sum
}

function Get-NativeMetricValue {
    param($Diagnostic, [string] $MetricName)
    $metrics = [string] $Diagnostic.native_metrics_prometheus
    return Get-PrometheusMetricValue -MetricsText $metrics -MetricName $MetricName
}

function Get-PrometheusMetricValue {
    param([string] $MetricsText, [string] $MetricName)
    if ([string]::IsNullOrWhiteSpace($MetricsText)) {
        return 0
    }
    $pattern = "(?m)^$([regex]::Escape($MetricName))(?:\{[^}]*\})?\s+([0-9.]+)\s*$"
    $match = [regex]::Match($MetricsText, $pattern)
    if ($match.Success) {
        return [int64] ([double] $match.Groups[1].Value)
    }
    return 0
}

function Get-ResponsePoolRetainedBytes {
    param($Diagnostic)
    $sum = [int64] 0
    foreach ($size in @("small", "medium", "large", "huge")) {
        $sum += Get-NativeMetricValue -Diagnostic $Diagnostic `
            -MetricName ("reactor_native_response_pool_{0}_retained_bytes" -f $size)
    }
    return $sum
}

function Get-XssBytes {
    param([string] $JavaOpts)
    $match = [regex]::Match($JavaOpts, "(?:^|\s)-Xss([0-9]+)([kKmMgG]?)")
    if (-not $match.Success) {
        return 0
    }
    $value = [int64] $match.Groups[1].Value
    switch ($match.Groups[2].Value.ToLowerInvariant()) {
        "g" { return $value * 1024L * 1024L * 1024L }
        "m" { return $value * 1024L * 1024L }
        "k" { return $value * 1024L }
        default { return $value }
    }
}

function Get-NativeProp {
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

function Get-RustAccountedBytes {
    param($Diagnostic)
    $native = $Diagnostic.native
    if ($null -eq $native) {
        return 0
    }
    $staticInline = [int64] (Get-NativeProp (Get-NativeProp $native "static_responses" $null) "file_inline_bytes" 0)
    $responseCache = [int64] (Get-NativeProp (Get-NativeProp $native "response_cache" $null) "bytes" 0)
    $bodyInFlight = [int64] (Get-NativeProp (Get-NativeProp $native "limiters" $null) "body_bytes_used" 0)
    $responseInFlight = [int64] (Get-NativeProp (Get-NativeProp $native "limiters" $null) "response_bytes_used" 0)
    $responsePoolRetained = Get-ResponsePoolRetainedBytes -Diagnostic $Diagnostic
    return $staticInline + $responseCache + $bodyInFlight + $responseInFlight + $responsePoolRetained
}

function Get-MapCategory {
    param([string] $Path)
    if ([string]::IsNullOrWhiteSpace($Path)) {
        return "anonymous"
    }
    if ($Path -eq "[heap]") {
        return "process-heap"
    }
    if ($Path.StartsWith("[stack")) {
        return "thread-stack"
    }
    if ($Path -match "librust_hyper|rust_hyper") {
        return "rust-native-lib"
    }
    if ($Path -match "libj9|libjvm|libomr|j9jit|j9gc|j9vm|openj9|compressedrefs") {
        return "openj9-native-lib"
    }
    if ($Path -match "\.jar$|/app/app\.jar|/app/lib/") {
        return "jar-mapped"
    }
    if ($Path -match "^/usr/lib|^/lib|ld-linux|libc\.so|libpthread|libstdc\+\+|libgcc|libssl|libcrypto") {
        return "system-native-lib"
    }
    if ($Path -match "^\[anon") {
        return "anonymous"
    }
    return "file-mapped-other"
}

function Parse-Smaps {
    param([string] $Text)

    $rows = New-Object 'System.Collections.Generic.List[object]'
    $current = $null
    foreach ($line in ($Text -split "`n")) {
        $trimmed = $line.TrimEnd("`r")
        if ($trimmed -match "^[0-9a-fA-F]+-[0-9a-fA-F]+\s+") {
            if ($null -ne $current) {
                $rows.Add([PSCustomObject] $current)
            }
            $parts = $trimmed -split "\s+", 6
            $path = ""
            if ($parts.Count -ge 6) {
                $path = $parts[5]
            }
            $current = [ordered]@{
                category = Get-MapCategory -Path $path
                path = $path
                size_kb = 0
                rss_kb = 0
                pss_kb = 0
                private_clean_kb = 0
                private_dirty_kb = 0
                shared_clean_kb = 0
                shared_dirty_kb = 0
                anonymous_kb = 0
                swap_kb = 0
            }
            continue
        }
        if ($null -eq $current) {
            continue
        }
        if ($trimmed -match "^([A-Za-z_]+):\s+([0-9]+)\s+kB") {
            $key = $matches[1]
            $value = [int64] $matches[2]
            switch ($key) {
                "Size" { $current.size_kb = $value }
                "Rss" { $current.rss_kb = $value }
                "Pss" { $current.pss_kb = $value }
                "Private_Clean" { $current.private_clean_kb = $value }
                "Private_Dirty" { $current.private_dirty_kb = $value }
                "Shared_Clean" { $current.shared_clean_kb = $value }
                "Shared_Dirty" { $current.shared_dirty_kb = $value }
                "Anonymous" { $current.anonymous_kb = $value }
                "Swap" { $current.swap_kb = $value }
            }
        }
    }
    if ($null -ne $current) {
        $rows.Add([PSCustomObject] $current)
    }
    return $rows
}

function Collect-OpenJ9Evidence {
    $targetDir = Join-Path $ResultsDir "openj9_evidence"
    New-Item -ItemType Directory -Force -Path $targetDir | Out-Null

    Invoke-ContainerText "cat /proc/1/status" |
        Set-Content -Path (Join-Path $targetDir "status_before_javacore.txt") -Encoding UTF8
    Invoke-ContainerText "cat /proc/1/limits" |
        Set-Content -Path (Join-Path $targetDir "limits.txt") -Encoding UTF8
    Invoke-ContainerText "ps -T -p 1 -o pid,tid,comm,stat,pri,psr,pcpu,pmem,vsz,rss 2>/dev/null || true" |
        Set-Content -Path (Join-Path $targetDir "threads.txt") -Encoding UTF8
    Invoke-ContainerText "which jcmd >/dev/null 2>&1 && jcmd 1 help 2>&1 || true" |
        Set-Content -Path (Join-Path $targetDir "jcmd_help.txt") -Encoding UTF8

    Invoke-ContainerText "rm -f /app/javacore*.txt /app/Snap*.trc /app/core*.dmp 2>/dev/null || true; kill -3 1; sleep 3; ls -1 /app/javacore*.txt /app/Snap*.trc /app/core*.dmp 2>/dev/null || true" |
        Set-Content -Path (Join-Path $targetDir "generated_files.txt") -Encoding UTF8

    $remoteFiles = @(
        Get-Content (Join-Path $targetDir "generated_files.txt") -ErrorAction SilentlyContinue |
            ForEach-Object { $_.Trim() } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    )
    foreach ($remote in $remoteFiles) {
        $fileName = Split-Path $remote -Leaf
        docker cp "${Container}:$remote" (Join-Path $targetDir $fileName) *> $null
    }

    Invoke-ContainerText "cat /proc/1/status" |
        Set-Content -Path (Join-Path $targetDir "status_after_javacore.txt") -Encoding UTF8
    return $targetDir
}

function Save-Phase {
    param([string] $Phase)

    $safe = $Phase -replace '[^a-zA-Z0-9_-]+', '_'
    $phaseDir = Join-Path $ResultsDir $safe
    New-Item -ItemType Directory -Force -Path $phaseDir | Out-Null

    $diagnosticsRaw = (Invoke-WebRequest -UseBasicParsing "$HostBaseUrl/diagnostics/memory" -TimeoutSec 10).Content
    $diagnosticsRaw | Set-Content -Path (Join-Path $phaseDir "diagnostics.json") -Encoding UTF8
    $diagnostic = $diagnosticsRaw | ConvertFrom-Json
    $metricsRaw = (Invoke-WebRequest -UseBasicParsing "$HostBaseUrl/metrics" -TimeoutSec 10).Content
    $metricsRaw | Set-Content -Path (Join-Path $phaseDir "metrics_prometheus.txt") -Encoding UTF8

    $smapsRollup = Invoke-ContainerText "cat /proc/1/smaps_rollup"
    $smaps = Invoke-ContainerText "cat /proc/1/smaps"
    $status = Invoke-ContainerText "cat /proc/1/status"
    $maps = Invoke-ContainerText "cat /proc/1/maps"
    $cgroupCurrent = Invoke-ContainerText "cat /sys/fs/cgroup/memory.current 2>/dev/null || cat /sys/fs/cgroup/memory/memory.usage_in_bytes 2>/dev/null || true"
    $cgroupStat = Invoke-ContainerText "cat /sys/fs/cgroup/memory.stat 2>/dev/null || true"

    $smapsRollup | Set-Content -Path (Join-Path $phaseDir "smaps_rollup.txt") -Encoding UTF8
    $smaps | Set-Content -Path (Join-Path $phaseDir "smaps.txt") -Encoding UTF8
    $status | Set-Content -Path (Join-Path $phaseDir "status.txt") -Encoding UTF8
    $maps | Set-Content -Path (Join-Path $phaseDir "maps.txt") -Encoding UTF8
    $cgroupCurrent | Set-Content -Path (Join-Path $phaseDir "cgroup_memory_current.txt") -Encoding UTF8
    $cgroupStat | Set-Content -Path (Join-Path $phaseDir "cgroup_memory_stat.txt") -Encoding UTF8

    $mapRows = Parse-Smaps -Text $smaps
    $categoryRows = @(
        $mapRows |
            Group-Object category |
            ForEach-Object {
                [PSCustomObject]@{
                    phase = $Phase
                    category = $_.Name
                    mappings = $_.Count
                    size_mib = Convert-KbToMiB (($_.Group | Measure-Object size_kb -Sum).Sum)
                    rss_mib = Convert-KbToMiB (($_.Group | Measure-Object rss_kb -Sum).Sum)
                    pss_mib = Convert-KbToMiB (($_.Group | Measure-Object pss_kb -Sum).Sum)
                    private_clean_mib = Convert-KbToMiB (($_.Group | Measure-Object private_clean_kb -Sum).Sum)
                    private_dirty_mib = Convert-KbToMiB (($_.Group | Measure-Object private_dirty_kb -Sum).Sum)
                    shared_clean_mib = Convert-KbToMiB (($_.Group | Measure-Object shared_clean_kb -Sum).Sum)
                    shared_dirty_mib = Convert-KbToMiB (($_.Group | Measure-Object shared_dirty_kb -Sum).Sum)
                    anonymous_mib = Convert-KbToMiB (($_.Group | Measure-Object anonymous_kb -Sum).Sum)
                    swap_mib = Convert-KbToMiB (($_.Group | Measure-Object swap_kb -Sum).Sum)
                }
            } |
            Sort-Object rss_mib -Descending
    )
    $categoryRows | Export-Csv -Path (Join-Path $phaseDir "smaps_categories.csv") -NoTypeInformation -Encoding UTF8

    $rssKb = Read-KvKb -Text $smapsRollup -Name "Rss"
    $pssKb = Read-KvKb -Text $smapsRollup -Name "Pss"
    $privateCleanKb = Read-KvKb -Text $smapsRollup -Name "Private_Clean"
    $privateDirtyKb = Read-KvKb -Text $smapsRollup -Name "Private_Dirty"
    $sharedCleanKb = Read-KvKb -Text $smapsRollup -Name "Shared_Clean"
    $sharedDirtyKb = Read-KvKb -Text $smapsRollup -Name "Shared_Dirty"
    $anonymousKb = Read-KvKb -Text $smapsRollup -Name "Anonymous"
    $swapKb = Read-KvKb -Text $smapsRollup -Name "Swap"
    $vmRssKb = Read-StatusKb -Text $status -Name "VmRSS"
    $vmSizeKb = Read-StatusKb -Text $status -Name "VmSize"
    $threads = 0
    $threadMatch = [regex]::Match($status, "(?m)^Threads:\s+([0-9]+)")
    if ($threadMatch.Success) {
        $threads = [int] $threadMatch.Groups[1].Value
    }
    $cgroupBytes = 0
    $cgroupText = (($cgroupCurrent -split "`n") | Select-Object -First 1).Trim()
    if ($cgroupText -match "^[0-9]+$") {
        $cgroupBytes = [int64] $cgroupText
    }
    $cgroupAnonBytes = Read-CgroupStatBytes -Text $cgroupStat -Name "anon"
    $heapUsedBytes = [int64] $diagnostic.jvm.heap_used_bytes
    $heapCommittedBytes = [int64] $diagnostic.jvm.heap_committed_bytes
    $jitCodeUsedBytes = Get-MemoryPoolBytesByCategory -Diagnostic $diagnostic -Category "jit-code" -Field "used_bytes"
    $jitCodeCommittedBytes = Get-MemoryPoolBytesByCategory -Diagnostic $diagnostic -Category "jit-code" -Field "committed_bytes"
    $classMetadataUsedBytes = Get-MemoryPoolBytesByCategory -Diagnostic $diagnostic -Category "class-metadata" -Field "used_bytes"
    $classMetadataCommittedBytes = Get-MemoryPoolBytesByCategory -Diagnostic $diagnostic -Category "class-metadata" -Field "committed_bytes"
    $nonHeapOtherUsedBytes = Get-MemoryPoolBytesByCategory -Diagnostic $diagnostic -Category "non-heap-other" -Field "used_bytes"
    $nonHeapOtherCommittedBytes = Get-MemoryPoolBytesByCategory -Diagnostic $diagnostic -Category "non-heap-other" -Field "committed_bytes"
    $directBufferBytes = Get-BufferPoolBytes $diagnostic "direct"
    $responsePoolRetainedBytes = Get-ResponsePoolRetainedBytes -Diagnostic $diagnostic
    $rustAccountedBytes = Get-RustAccountedBytes $diagnostic
    $xssBytes = Get-XssBytes -JavaOpts $profile.JavaOpts
    $threadStackBudgetBytes = [int64] $threads * [int64] $xssBytes
    $strongAccountedBytes = $heapUsedBytes `
        + $jitCodeUsedBytes `
        + $classMetadataUsedBytes `
        + $nonHeapOtherUsedBytes `
        + $directBufferBytes `
        + $rustAccountedBytes
    $committedUpperBytes = $heapCommittedBytes `
        + $jitCodeCommittedBytes `
        + $classMetadataCommittedBytes `
        + $nonHeapOtherCommittedBytes `
        + $directBufferBytes `
        + $rustAccountedBytes
    $residualBytes = $cgroupAnonBytes - $strongAccountedBytes
    $residualAfterStackBudgetBytes = $residualBytes - $threadStackBudgetBytes

    [PSCustomObject]@{
        phase = $Phase
        smaps_rss_mib = Convert-KbToMiB $rssKb
        smaps_pss_mib = Convert-KbToMiB $pssKb
        private_clean_mib = Convert-KbToMiB $privateCleanKb
        private_dirty_mib = Convert-KbToMiB $privateDirtyKb
        shared_clean_mib = Convert-KbToMiB $sharedCleanKb
        shared_dirty_mib = Convert-KbToMiB $sharedDirtyKb
        anonymous_mib = Convert-KbToMiB $anonymousKb
        swap_mib = Convert-KbToMiB $swapKb
        vmrss_mib = Convert-KbToMiB $vmRssKb
        vmsize_mib = Convert-KbToMiB $vmSizeKb
        cgroup_current_mib = Convert-BytesToMiB $cgroupBytes
        cgroup_anon_mib = Convert-BytesToMiB $cgroupAnonBytes
        cgroup_file_mib = Convert-BytesToMiB (Read-CgroupStatBytes -Text $cgroupStat -Name "file")
        cgroup_kernel_stack_mib = Convert-BytesToMiB (Read-CgroupStatBytes -Text $cgroupStat -Name "kernel_stack")
        cgroup_pagetables_mib = Convert-BytesToMiB (Read-CgroupStatBytes -Text $cgroupStat -Name "pagetables")
        cgroup_slab_mib = Convert-BytesToMiB (Read-CgroupStatBytes -Text $cgroupStat -Name "slab")
        cgroup_sock_mib = Convert-BytesToMiB (Read-CgroupStatBytes -Text $cgroupStat -Name "sock")
        cgroup_file_mapped_mib = Convert-BytesToMiB (Read-CgroupStatBytes -Text $cgroupStat -Name "file_mapped")
        heap_used_mib = Convert-BytesToMiB $heapUsedBytes
        heap_committed_mib = Convert-BytesToMiB $heapCommittedBytes
        non_heap_used_mib = Convert-BytesToMiB ([int64] $diagnostic.jvm.non_heap_used_bytes)
        non_heap_committed_mib = Convert-BytesToMiB ([int64] $diagnostic.jvm.non_heap_committed_bytes)
        jit_code_used_mib = Convert-BytesToMiB $jitCodeUsedBytes
        jit_code_committed_mib = Convert-BytesToMiB $jitCodeCommittedBytes
        class_metadata_used_mib = Convert-BytesToMiB $classMetadataUsedBytes
        class_metadata_committed_mib = Convert-BytesToMiB $classMetadataCommittedBytes
        non_heap_other_used_mib = Convert-BytesToMiB $nonHeapOtherUsedBytes
        non_heap_other_committed_mib = Convert-BytesToMiB $nonHeapOtherCommittedBytes
        direct_buffer_mib = Convert-BytesToMiB $directBufferBytes
        mapped_buffer_mib = Convert-BytesToMiB (Get-BufferPoolBytes $diagnostic "mapped")
        rust_response_pool_retained_mib = Convert-BytesToMiB $responsePoolRetainedBytes
        rust_accounted_mib = Convert-BytesToMiB $rustAccountedBytes
        native_http_requests_total = Get-PrometheusMetricValue -MetricsText $metricsRaw -MetricName "reactor_native_http_requests_total"
        native_http_user_requests_total = Get-PrometheusMetricValue -MetricsText $metricsRaw -MetricName "reactor_native_http_user_requests_total"
        native_trim_attempts = Get-PrometheusMetricValue -MetricsText $metricsRaw -MetricName "reactor_native_trim_attempts_total"
        native_trim_success = Get-PrometheusMetricValue -MetricsText $metricsRaw -MetricName "reactor_native_trim_success_total"
        native_trim_skipped_active = Get-PrometheusMetricValue -MetricsText $metricsRaw -MetricName "reactor_native_trim_skipped_active_total"
        native_trim_skipped_not_idle = Get-PrometheusMetricValue -MetricsText $metricsRaw -MetricName "reactor_native_trim_skipped_not_idle_total"
        native_trim_skipped_unchanged = Get-PrometheusMetricValue -MetricsText $metricsRaw -MetricName "reactor_native_trim_skipped_unchanged_total"
        native_trim_errors = Get-PrometheusMetricValue -MetricsText $metricsRaw -MetricName "reactor_native_trim_errors_total"
        native_trim_last_duration_ms = Get-PrometheusMetricValue -MetricsText $metricsRaw -MetricName "reactor_native_trim_last_duration_ms"
        xss_bytes = $xssBytes
        thread_stack_budget_mib = Convert-BytesToMiB $threadStackBudgetBytes
        anon_strong_accounted_mib = Convert-BytesToMiB $strongAccountedBytes
        anon_committed_upper_mib = Convert-BytesToMiB $committedUpperBytes
        anon_residual_mib = Convert-BytesToMiB $residualBytes
        anon_residual_after_stack_budget_mib = Convert-BytesToMiB $residualAfterStackBudgetBytes
        jvm_threads = [int] $diagnostic.jvm.thread_count
        linux_threads = $threads
        loaded_classes = [int] $diagnostic.jvm.loaded_class_count
    }
}

function Convert-LatencyTextToMs {
    param([string] $Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return 0.0
    }
    $text = $Value.Trim()
    if ($text -match "^([0-9.]+)\s*(us|µs)$") {
        return [double] $matches[1] / 1000.0
    }
    if ($text -match "^([0-9.]+)\s*ms$") {
        return [double] $matches[1]
    }
    if ($text -match "^([0-9.]+)\s*s$") {
        return [double] $matches[1] * 1000.0
    }
    if ($text -match "^([0-9.]+)$") {
        return [double] $matches[1]
    }
    return 0.0
}

function Convert-LoadProbeText {
    param([string] $Text)

    $requests = 0
    $errors = 0
    $rps = 0.0
    $avg = 0.0
    $p95 = 0.0
    $p99 = 0.0
    $statuses = @{}

    $match = [regex]::Match($Text, "(?m)completed requests:\s+([0-9]+)")
    if ($match.Success) {
        $requests = [int64] $match.Groups[1].Value
    }
    $match = [regex]::Match($Text, "(?m)errors total:\s+([0-9]+)")
    if ($match.Success) {
        $errors = [int64] $match.Groups[1].Value
    }
    $match = [regex]::Match($Text, "(?m)Requests/sec:\s+([0-9.]+)")
    if ($match.Success) {
        $rps = [double] $match.Groups[1].Value
    }
    $match = [regex]::Match($Text, "(?m)^\s+Latency\s+([0-9.]+(?:us|µs|ms|s))")
    if ($match.Success) {
        $avg = Convert-LatencyTextToMs $match.Groups[1].Value
    }
    $match = [regex]::Match($Text, "(?m)^\s+95%\s+([0-9.]+(?:us|µs|ms|s))")
    if ($match.Success) {
        $p95 = Convert-LatencyTextToMs $match.Groups[1].Value
    }
    $match = [regex]::Match($Text, "(?m)^\s+99%\s+([0-9.]+(?:us|µs|ms|s))")
    if ($match.Success) {
        $p99 = Convert-LatencyTextToMs $match.Groups[1].Value
    }
    foreach ($statusMatch in [regex]::Matches($Text, "(?m)^Status\s+([0-9]+):\s+([0-9]+)")) {
        $statuses[$statusMatch.Groups[1].Value] = [int64] $statusMatch.Groups[2].Value
    }

    [PSCustomObject]@{
        requests = $requests
        errors_total = $errors
        statuses = $statuses
        rps = $rps
        avg_ms = $avg
        p95_ms = $p95
        p99_ms = $p99
    }
}

function Invoke-Load {
    param([string] $Name, [string] $Path, [int] $Concurrency)

    $raw = & docker run --rm --network "container:$Container" --entrypoint load-probe $RunnerImage `
        --url "http://127.0.0.1:8080$Path" `
        --concurrency $Concurrency `
        --duration "${DurationSeconds}s" `
        --timeout-ms 10000
    if ($LASTEXITCODE -ne 0) {
        throw "load-probe failed for $Name c$Concurrency"
    }
    $safe = $Name -replace '[^a-zA-Z0-9_-]+', '_'
    $out = Join-Path $ResultsDir ("load_{0}_c{1}.txt" -f $safe, $Concurrency)
    $raw | Set-Content -Path $out -Encoding UTF8
    $rawText = $raw -join "`n"
    $jsonText = [regex]::Match($rawText, "(?s)\{.*\}").Value
    if (-not [string]::IsNullOrWhiteSpace($jsonText)) {
        $json = $jsonText | ConvertFrom-Json
        $requests = $json.requests
        $errors = $json.errors_total
        $statuses = ($json.statuses | ConvertTo-Json -Compress)
        $rps = [Math]::Round($json.rps, 2)
        $avg = [Math]::Round($json.latency_us.avg / 1000.0, 3)
        $p95 = [Math]::Round($json.latency_us.p95 / 1000.0, 3)
        $p99 = [Math]::Round($json.latency_us.p99 / 1000.0, 3)
    } else {
        $parsed = Convert-LoadProbeText -Text $rawText
        $requests = $parsed.requests
        $errors = $parsed.errors_total
        $statuses = ($parsed.statuses | ConvertTo-Json -Compress)
        $rps = [Math]::Round($parsed.rps, 2)
        $avg = [Math]::Round($parsed.avg_ms, 3)
        $p95 = [Math]::Round($parsed.p95_ms, 3)
        $p99 = [Math]::Round($parsed.p99_ms, 3)
    }
    [PSCustomObject]@{
        endpoint = $Name
        path = $Path
        concurrency = $Concurrency
        requests = $requests
        errors_total = $errors
        statuses = $statuses
        rps = $rps
        avg_ms = $avg
        p95_ms = $p95
        p99_ms = $p99
    }
}

function Write-Report {
    param($Rows, $LoadRows)

    $summaryCsv = Join-Path $ResultsDir "linux_smaps_summary.csv"
    $loadCsv = Join-Path $ResultsDir "load_results.csv"
    $Rows | Export-Csv -Path $summaryCsv -NoTypeInformation -Encoding UTF8
    $LoadRows | Export-Csv -Path $loadCsv -NoTypeInformation -Encoding UTF8

    $baseline = $Rows | Where-Object { $_.phase -eq "00_baseline" } | Select-Object -First 1
    $peak = $Rows | Sort-Object smaps_rss_mib -Descending | Select-Object -First 1
    $final = $Rows | Where-Object { $_.phase -eq "99_final_idle" } | Select-Object -First 1

    $report = Join-Path $ResultsDir "linux_smaps_breakdown_report.md"
    $lines = New-Object 'System.Collections.Generic.List[string]'
    $lines.Add("# Linux smaps RSS Breakdown")
    $lines.Add("")
    $lines.Add("- Date: $(Get-Date -Format o)")
    $lines.Add("- Image: $Image")
    $imageId = (& docker image inspect --format "{{.Id}}" $Image 2>$null | Select-Object -First 1)
    if (-not [string]::IsNullOrWhiteSpace($imageId)) {
        $lines.Add("- Image ID: $($imageId.Trim())")
    }
    $lines.Add("- Runtime profile: $RuntimeProfile")
    $lines.Add("- App mode: $AppMode")
    if (-not [string]::IsNullOrWhiteSpace($JvmXss)) {
        $lines.Add("- JVM Xss override: $JvmXss")
    }
    if (-not [string]::IsNullOrWhiteSpace($effectiveExtraJavaOpts)) {
        $lines.Add("- Extra Java opts: $effectiveExtraJavaOpts")
    }
    $lines.Add("- JAVA_TOOL_OPTIONS: ``$JavaToolOptions``")
    $lines.Add("- Container memory limit: $((Get-ProfileConfig).Memory)")
    $lines.Add("- Duration per load phase: ${DurationSeconds}s")
    $lines.Add("- Trim before final idle: $TrimBeforeFinalIdle")
    if ($FinalIdleSnapshotValues.Count -gt 0) {
        $lines.Add("- Final idle snapshots: $($FinalIdleSnapshotValues -join ',') seconds")
    }
    $lines.Add("- Summary CSV: $summaryCsv")
    $lines.Add("- Load CSV: $loadCsv")
    if (-not [string]::IsNullOrWhiteSpace($JavacoreEvidenceDir)) {
        $lines.Add("- OpenJ9 evidence dir: $JavacoreEvidenceDir")
    }
    $lines.Add("")
    $lines.Add("## Interpretation Rules")
    $lines.Add("")
    $lines.Add("- smaps RSS/PSS/private dirty values are Linux OS-level memory evidence and are closer to Kubernetes RSS behavior than Windows WorkingSet64.")
    $lines.Add("- JVM heap/non-heap/direct values come from Java MXBeans. They are not the same taxonomy as smaps mappings.")
    $lines.Add("- OpenJ9 Java heap pages usually appear as anonymous/private mappings in smaps; without JVM native-memory tagging they cannot be mapped one-to-one to Java heap objects.")
    $lines.Add("- rust_accounted_mib is only memory explicitly reported by framework native diagnostics. Rust/Tokio allocator pages can still appear under anonymous/private dirty or native library mappings.")
    $lines.Add("- anon_residual_mib is cgroup anon minus strongly accounted heap/non-heap/direct/Rust bytes. It still contains actual thread stack residency, JVM native allocator pages, Rust/Tokio allocator pages not exposed by metrics, and attribution error.")
    $lines.Add("- thread_stack_budget_mib is an upper-bound budget from Linux thread count * -Xss. It is not exact resident stack RSS.")
    $lines.Add("")
    $lines.Add("## Top-Level Result")
    $lines.Add("")
    $lines.Add("| Metric | Value |")
    $lines.Add("|---|---:|")
    if ($baseline) {
        $lines.Add("| Baseline smaps RSS MiB | $($baseline.smaps_rss_mib) |")
        $lines.Add("| Baseline cgroup current MiB | $($baseline.cgroup_current_mib) |")
    }
    if ($peak) {
        $lines.Add("| Peak smaps RSS MiB | $($peak.smaps_rss_mib) |")
        $lines.Add("| Peak phase | $($peak.phase) |")
    }
    if ($final) {
        $lines.Add("| Final idle smaps RSS MiB | $($final.smaps_rss_mib) |")
        $lines.Add("| Final idle cgroup current MiB | $($final.cgroup_current_mib) |")
        $lines.Add("| Native HTTP requests total | $($final.native_http_requests_total) |")
        $lines.Add("| Native HTTP user requests total | $($final.native_http_user_requests_total) |")
        $lines.Add("| Native trim success total | $($final.native_trim_success) |")
        $lines.Add("| Native trim skipped active total | $($final.native_trim_skipped_active) |")
        $lines.Add("| Native trim skipped not-idle total | $($final.native_trim_skipped_not_idle) |")
        $lines.Add("| Native trim last duration ms | $($final.native_trim_last_duration_ms) |")
        if ($baseline) {
            $lines.Add("| Final - baseline smaps RSS MiB | $([Math]::Round($final.smaps_rss_mib - $baseline.smaps_rss_mib, 3)) |")
        }
    }
    $lines.Add("")
    $lines.Add("## Phase Summary")
    $lines.Add("")
    $lines.Add("| Phase | RSS | PSS | Private Dirty | Anonymous | Shared Clean | Cgroup Current | Heap Used | Non-Heap Used | Direct | Rust Accounted | Threads | Classes |")
    $lines.Add("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    foreach ($row in $Rows) {
        $lines.Add("| $($row.phase) | $($row.smaps_rss_mib) | $($row.smaps_pss_mib) | $($row.private_dirty_mib) | $($row.anonymous_mib) | $($row.shared_clean_mib) | $($row.cgroup_current_mib) | $($row.heap_used_mib) | $($row.non_heap_used_mib) | $($row.direct_buffer_mib) | $($row.rust_accounted_mib) | $($row.linux_threads) | $($row.loaded_classes) |")
    }
    $lines.Add("")
    $lines.Add("## Cgroup Memory Stat")
    $lines.Add("")
    $lines.Add("| Phase | memory.current | anon | file | file_mapped | kernel_stack | pagetables | slab | sock |")
    $lines.Add("|---|---:|---:|---:|---:|---:|---:|---:|---:|")
    foreach ($row in $Rows) {
        $lines.Add("| $($row.phase) | $($row.cgroup_current_mib) | $($row.cgroup_anon_mib) | $($row.cgroup_file_mib) | $($row.cgroup_file_mapped_mib) | $($row.cgroup_kernel_stack_mib) | $($row.cgroup_pagetables_mib) | $($row.cgroup_slab_mib) | $($row.cgroup_sock_mib) |")
    }
    $lines.Add("")
    $lines.Add("## Anon Attribution Summary")
    $lines.Add("")
    $lines.Add("This table is intentionally split into used/accounted values, committed upper budget, and residual anon. Used/accounted values come from JVM MXBeans and native framework metrics; residual anon is where untagged JVM/Rust/native allocator pages remain.")
    $lines.Add("")
    $lines.Add("| Phase | Cgroup anon | Heap used | JIT/code used | Class metadata used | Non-heap other used | Direct buffer | Rust accounted | Thread stack budget upper | Used/accounted | Committed upper budget | Residual anon | Residual after stack budget |")
    $lines.Add("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    foreach ($row in $Rows) {
        $lines.Add("| $($row.phase) | $($row.cgroup_anon_mib) | $($row.heap_used_mib) | $($row.jit_code_used_mib) | $($row.class_metadata_used_mib) | $($row.non_heap_other_used_mib) | $($row.direct_buffer_mib) | $($row.rust_accounted_mib) | $($row.thread_stack_budget_mib) | $($row.anon_strong_accounted_mib) | $($row.anon_committed_upper_mib) | $($row.anon_residual_mib) | $($row.anon_residual_after_stack_budget_mib) |")
    }
    $lines.Add("")
    $lines.Add("## Native Idle Trim")
    $lines.Add("")
    $lines.Add("| Phase | Total req | User req | Attempts | Success | Skipped active | Skipped not idle | Skipped unchanged | Errors | Last duration ms |")
    $lines.Add("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    foreach ($row in $Rows) {
        $lines.Add("| $($row.phase) | $($row.native_http_requests_total) | $($row.native_http_user_requests_total) | $($row.native_trim_attempts) | $($row.native_trim_success) | $($row.native_trim_skipped_active) | $($row.native_trim_skipped_not_idle) | $($row.native_trim_skipped_unchanged) | $($row.native_trim_errors) | $($row.native_trim_last_duration_ms) |")
    }
    $lines.Add("")
    $lines.Add("## Category Breakdown For Key Phases")
    foreach ($phase in @("00_baseline", "01_warmup", "after_dynamic-dto_c256", "99_final_idle")) {
        $categoryPath = Join-Path (Join-Path $ResultsDir $phase) "smaps_categories.csv"
        if (-not (Test-Path $categoryPath)) {
            continue
        }
        $lines.Add("")
        $lines.Add("### $phase")
        $lines.Add("")
        $lines.Add("| Category | Mappings | RSS MiB | PSS MiB | Private Dirty MiB | Anonymous MiB |")
        $lines.Add("|---|---:|---:|---:|---:|---:|")
        foreach ($category in (Import-Csv $categoryPath | Sort-Object {[double]($_.rss_mib -replace ',', '.')} -Descending)) {
            $lines.Add("| $($category.category) | $($category.mappings) | $($category.rss_mib) | $($category.pss_mib) | $($category.private_dirty_mib) | $($category.anonymous_mib) |")
        }
    }
    $lines.Add("")
    $lines.Add("## Load Results")
    $lines.Add("")
    $lines.Add("| Endpoint | c | Requests | RPS | Avg ms | P95 ms | P99 ms | Errors | Statuses |")
    $lines.Add("|---|---:|---:|---:|---:|---:|---:|---:|---|")
    foreach ($load in $LoadRows) {
        $lines.Add("| $($load.endpoint) | $($load.concurrency) | $($load.requests) | $($load.rps) | $($load.avg_ms) | $($load.p95_ms) | $($load.p99_ms) | $($load.errors_total) | $($load.statuses) |")
    }
    $lines | Set-Content -Path $report -Encoding UTF8
    return $report
}

$profile = Get-ProfileConfig
if (-not [string]::IsNullOrWhiteSpace($JvmXss)) {
    $profile.JavaOpts = Set-JavaXssOption -JavaOpts $profile.JavaOpts -Xss $JvmXss
}
$explicitExtraOpts = New-Object 'System.Collections.Generic.List[string]'
if ($CodeCacheMaxRAMPercentage -gt 0) {
    $explicitExtraOpts.Add("-XX:codecachetotalMaxRAMPercentage=$CodeCacheMaxRAMPercentage")
}
if (-not [string]::IsNullOrWhiteSpace($CodeCacheTotal)) {
    $explicitExtraOpts.Add("-Xcodecachetotal$CodeCacheTotal")
}
if (-not [string]::IsNullOrWhiteSpace($ExtraJavaOpts)) {
    $explicitExtraOpts.Add($ExtraJavaOpts)
}
$effectiveExtraJavaOpts = Join-JavaOptions $explicitExtraOpts
if (-not [string]::IsNullOrWhiteSpace($effectiveExtraJavaOpts)) {
    $profile.JavaOpts = Join-JavaOptions @($profile.JavaOpts, $effectiveExtraJavaOpts)
}
$Rows = New-Object 'System.Collections.Generic.List[object]'
$LoadRows = New-Object 'System.Collections.Generic.List[object]'

try {
    if (-not $SkipBuild) {
        & mvn -q -DskipTests -f (Join-Path $FrameworkRoot "pom.xml") install
        if ($LASTEXITCODE -ne 0) {
            throw "mvn package failed"
        }
        if ($AppMode -eq "minimal") {
            $frameworkJar = Find-FrameworkCoreRuntimeJar
            docker build -t $Image -f (Join-Path $FrameworkRoot "benchmark/docker/minimal-production.Dockerfile") --build-arg "CORE_JAR=$frameworkJar" $FrameworkRoot
        } else {
            & mvn -q -DskipTests -f (Join-Path $FrameworkRoot "sample\pom.xml") package
            if ($LASTEXITCODE -ne 0) {
                throw "sample mvn package failed"
            }
            $frameworkJar = Find-FrameworkSampleJar
            docker build -t $Image -f (Join-Path $FrameworkRoot "benchmark/docker/framework.Dockerfile") --build-arg "JAR_FILE=$frameworkJar" $FrameworkRoot
        }
        if ($LASTEXITCODE -ne 0) {
            throw "framework docker build failed"
        }
        docker build -q -t $RunnerImage -f (Join-Path $ScriptDir "Dockerfile.benchmark") $ScriptDir | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "runner docker build failed"
        }
    }

    Remove-ContainerIfExists -Name $Container
    $containerId = docker run -d --name $Container --memory $profile.Memory -p "${HostPort}:8080" -e "JAVA_TOOL_OPTIONS=$JavaToolOptions" -e "JAVA_OPTS=$($profile.JavaOpts)" $Image
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($containerId)) {
        throw "Failed to start $Container."
    }

    Wait-Ready
    $Rows.Add((Save-Phase -Phase "00_baseline"))
    Invoke-WebRequest -UseBasicParsing "$HostBaseUrl/metrics/reset" -TimeoutSec 10 | Out-Null
    foreach ($spec in $EndpointSpecs) {
        $parts = $spec -split "\|", 2
        Invoke-WebRequest -UseBasicParsing "$HostBaseUrl$($parts[1])" -TimeoutSec 10 | Out-Null
    }
    $Rows.Add((Save-Phase -Phase "01_warmup"))

    foreach ($concurrency in $ConcurrencyValues) {
        foreach ($spec in $EndpointSpecs) {
            $parts = $spec -split "\|", 2
            $name = $parts[0]
            $path = $parts[1]
            $load = Invoke-Load -Name $name -Path $path -Concurrency $concurrency
            $LoadRows.Add($load)
            $Rows.Add((Save-Phase -Phase ("after_{0}_c{1}" -f $name, $concurrency)))
            Start-Sleep -Seconds $IdleSeconds
            $Rows.Add((Save-Phase -Phase ("idle_{0}_c{1}" -f $name, $concurrency)))
        }
    }

    if ($TrimBeforeFinalIdle) {
        Invoke-WebRequest -UseBasicParsing "$HostBaseUrl/diagnostics/native/trim" -TimeoutSec 10 | Out-Null
        Start-Sleep -Seconds 1
        $Rows.Add((Save-Phase -Phase "98_after_native_trim"))
    }

    if ($FinalIdleSnapshotValues.Count -gt 0) {
        $elapsedFinalIdleSeconds = 0
        for ($i = 0; $i -lt $FinalIdleSnapshotValues.Count; $i++) {
            $targetSeconds = $FinalIdleSnapshotValues[$i]
            $sleepSeconds = $targetSeconds - $elapsedFinalIdleSeconds
            if ($sleepSeconds -gt 0) {
                Start-Sleep -Seconds $sleepSeconds
            }
            $elapsedFinalIdleSeconds = $targetSeconds
            $phaseName = if ($i -eq $FinalIdleSnapshotValues.Count - 1) {
                "99_final_idle"
            } else {
                "98_idle_${targetSeconds}s"
            }
            $Rows.Add((Save-Phase -Phase $phaseName))
        }
    } elseif ($FinalIdleSeconds -gt 0) {
        Start-Sleep -Seconds $FinalIdleSeconds
        $Rows.Add((Save-Phase -Phase "99_final_idle"))
    } else {
        $Rows.Add((Save-Phase -Phase "99_final_idle"))
    }
    if ($CollectJavacore) {
        $JavacoreEvidenceDir = Collect-OpenJ9Evidence
    }
    $report = Write-Report -Rows $Rows -LoadRows $LoadRows
    Write-Output "linux smaps breakdown report: $report"
} finally {
    try {
        $existing = @(& docker ps -a --format "{{.Names}}" | Where-Object { $_ -eq $Container })
        if ($existing.Count -gt 0) {
            $logPath = Join-Path $ResultsDir "$Container.log"
            $escapedLogPath = $logPath.Replace('"', '\"')
            & cmd.exe /d /c "docker logs $Container > `"$escapedLogPath`" 2>&1"
        }
    } catch {
        Write-Warning "Failed to capture container logs: $($_.Exception.Message)"
    }
    if (-not $KeepContainer) {
        Remove-ContainerIfExists -Name $Container
    }
}
