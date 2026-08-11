param(
    [object] $ConcurrencyLevels = @(64, 256, 512, 1000),
    [string] $Duration = "20s",
    [string] $Warmup = "5s",
    [int] $Threads = 4,
    [double] $CpuLimit = 2.0,
    [ValidateSet("micro-rest", "micro-rest-plus", "micro-rss", "ultra-low-rss", "low-rss", "balanced", "throughput")]
    [string] $RuntimeProfile = "low-rss",
    [string] $FrameworkMemory = "",
    [string] $SpringMemory = "",
    [string] $ResultsDir = "",
    [int] $RepeatCount = 1,
    [bool] $RandomizeOrder = $true,
    [int] $RandomSeed = 0,
    [string] $EndpointClasses = "",
    [ValidateSet("current", "cpu1", "cpu1-xss192", "cpu1-xss160", "cpu1-xss128", "cpu1-nojit", "cpu1-nojit-xss160", "cpu1-nojit-xss128")]
    [string] $FrameworkJvmPreset = "current",
    [double] $FrameworkCodeCacheMaxRAMPercentage = 0,
    [string] $FrameworkCodeCacheTotal = "",
    [string] $FrameworkJavaOptsAppend = "",
    [string] $FrameworkJavaToolOptions = "",
    [switch] $PlanPreWarm,
    [string] $PlanPreWarmDuration = "3s",
    [switch] $FrameworkOnly,
    [switch] $SkipBuild,
    [switch] $SkipImageBuild,
    [switch] $SkipRunnerImageBuild,
    [switch] $KeepContainers
)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$FrameworkRoot = Split-Path -Parent $ScriptDir
$WorkspaceRoot = Split-Path -Parent $FrameworkRoot
$SpringRoot = Join-Path $WorkspaceRoot "spring-boot-simple-rest-api\com.divit.spring-boot-simple-rest-api"

$NetworkName = "reactor-benchmark-net"
$FrameworkImage = "rust-java-rest:benchmark"
$SpringImage = "spring-boot-rest:benchmark"
$RunnerImage = "reactor-benchmark-runner:local"
$FrameworkContainer = "rust-java-rest"
$SpringContainer = "spring-boot-rest"

if ([string]::IsNullOrWhiteSpace($ResultsDir)) {
    $timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
    $ResultsDir = Join-Path $ScriptDir "results\container_$timestamp"
}
$ResultsDir = [System.IO.Path]::GetFullPath($ResultsDir)
New-Item -ItemType Directory -Force $ResultsDir | Out-Null

if ($ConcurrencyLevels -is [array]) {
    $ConcurrencyValues = @($ConcurrencyLevels | ForEach-Object { [int] $_ })
} else {
    $ConcurrencyValues = @(
        "$ConcurrencyLevels" -split "[,\s]+" |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            ForEach-Object { [int] $_ }
    )
}
if ($ConcurrencyValues.Count -eq 0) {
    throw "At least one concurrency level is required."
}
if ($RepeatCount -lt 1) {
    throw "RepeatCount must be >= 1."
}
if ($RandomSeed -gt 0) {
    Get-Random -SetSeed $RandomSeed | Out-Null
}

$EndpointClassFilter = @()
if (-not [string]::IsNullOrWhiteSpace($EndpointClasses)) {
    $EndpointClassFilter = @(
        $EndpointClasses -split "[,\s]+" |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            ForEach-Object { $_.Trim() }
    )
}

function Join-JavaOptions {
    param([string[]] $Parts)
    return (($Parts | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join " ")
}

function Get-FrameworkJvmPresetOptions {
    param([string] $Preset)

    if ($Preset -eq "current") {
        return ""
    }

    $parts = New-Object System.Collections.Generic.List[string]
    if ($Preset -like "*xss128") {
        $parts.Add("-Xss128k")
    } elseif ($Preset -like "*xss160") {
        $parts.Add("-Xss160k")
    } elseif ($Preset -like "*xss192") {
        $parts.Add("-Xss192k")
    } else {
        $parts.Add("-Xss256k")
    }

    if ($Preset -like "cpu1*") {
        $parts.Add("-XX:ActiveProcessorCount=1")
    }
    if ($Preset -like "*nojit*") {
        $parts.Add("-Xnojit")
    }
    return Join-JavaOptions -Parts ([string[]]$parts)
}

function Get-RuntimeProfileConfig {
    param([string] $Profile)

    switch ($Profile) {
        "micro-rest" {
            return [PSCustomObject]@{
                Description = "small-pod REST profile with one JNI worker, narrow queues, no native cache, and controlled overload"
                FrameworkMemory = "80m"
                SpringMemory = "512m"
                FrameworkJavaOpts = Join-JavaOptions -Parts @(
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
                SpringJavaOpts = Join-JavaOptions -Parts @(
                    "-Xms64m",
                    "-Xmx256m",
                    "-Xquickstart",
                    "-Xtune:virtualized",
                    "-Xshareclasses:none",
                    "-Dserver.port=8080",
                    "-Dlogging.level.root=WARN",
                    "-Dfile.encoding=UTF-8",
                    "-Djava.security.egd=file:/dev/./urandom"
                )
            }
        }
        "micro-rest-plus" {
            return [PSCustomObject]@{
                Description = "micro-rest-plus runtime profile with route-workload heavy JSON budgets; lower direct-heavy 503 at the cost of some useful RPS/p99 headroom"
                FrameworkMemory = "80m"
                SpringMemory = "512m"
                FrameworkJavaOpts = Join-JavaOptions -Parts @(
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
                SpringJavaOpts = Join-JavaOptions -Parts @(
                    "-Xms64m",
                    "-Xmx256m",
                    "-Xquickstart",
                    "-Xtune:virtualized",
                    "-Xshareclasses:none",
                    "-Dserver.port=8080",
                    "-Dlogging.level.root=WARN",
                    "-Dfile.encoding=UTF-8",
                    "-Djava.security.egd=file:/dev/./urandom"
                )
            }
        }
        "micro-rss" {
            return [PSCustomObject]@{
                Description = "memory-first profile for cache/raw read-heavy endpoints; JIT disabled, minimal Java/Rust workers"
                FrameworkMemory = "80m"
                SpringMemory = "512m"
                FrameworkJavaOpts = Join-JavaOptions -Parts @(
                    "-Xms4m",
                    "-Xmx24m",
                    "-Xss256k",
                    "-Xquickstart",
                    "-Xtune:virtualized",
                    "-Xshareclasses:none",
                    "-Xnojit",
                    "-XX:ActiveProcessorCount=1",
                    "-Xgc:threads=1",
                    "-XX:-TransparentHugePage",
                    "-Dreactor.rust.jni.workers=1",
                    "-Dreactor.rust.jni.queue-capacity=128",
                    "-Dreactor.rust.json.writer-initial-bytes=4096",
                    "-Dreactor.rust.json.writer-retain-max-bytes=32768",
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
                    "-Dreactor.rust.log.level=error",
                    "-Dreactor.rust.java.log.level=warn",
                    "-Dfile.encoding=UTF-8",
                    "-Djava.security.egd=file:/dev/./urandom"
                )
                SpringJavaOpts = Join-JavaOptions -Parts @(
                    "-Xms64m",
                    "-Xmx256m",
                    "-Xquickstart",
                    "-Xtune:virtualized",
                    "-Xshareclasses:none",
                    "-Dserver.port=8080",
                    "-Dlogging.level.root=WARN",
                    "-Dfile.encoding=UTF-8",
                    "-Djava.security.egd=file:/dev/./urandom"
                )
            }
        }
        "ultra-low-rss" {
            return [PSCustomObject]@{
                Description = "aggressively bounded low-RSS profile for very small REST services with fail-fast overload behavior"
                FrameworkMemory = "80m"
                SpringMemory = "512m"
                FrameworkJavaOpts = Join-JavaOptions -Parts @(
                    "-Xms4m",
                    "-Xmx32m",
                    "-Xss256k",
                    "-Xquickstart",
                    "-Xtune:virtualized",
                    "-Xshareclasses:none",
                    "-XX:ActiveProcessorCount=1",
                    "-Xgc:threads=1",
                    "-XX:-TransparentHugePage",
                    "-Dreactor.runtime.profile=micro-rest",
                    "-Dreactor.rust.jni.workers=1",
                    "-Dreactor.rust.jni.queue-capacity=96",
                    "-Dreactor.rust.json.writer-initial-bytes=4096",
                    "-Dreactor.rust.json.writer-retain-max-bytes=16384",
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
                    "-Dfile.encoding=UTF-8",
                    "-Djava.security.egd=file:/dev/./urandom"
                )
                SpringJavaOpts = Join-JavaOptions -Parts @(
                    "-Xms64m",
                    "-Xmx256m",
                    "-Xquickstart",
                    "-Xtune:virtualized",
                    "-Xshareclasses:none",
                    "-Dserver.port=8080",
                    "-Dlogging.level.root=WARN",
                    "-Dfile.encoding=UTF-8",
                    "-Djava.security.egd=file:/dev/./urandom"
                )
            }
        }
        "low-rss" {
            return [PSCustomObject]@{
                Description = "tight heap/queue budgets for RSS regression checks"
                FrameworkMemory = "96m"
                SpringMemory = "512m"
                FrameworkJavaOpts = Join-JavaOptions -Parts @(
                    "-Xms8m",
                    "-Xmx40m",
                    "-Xquickstart",
                    "-Xtune:virtualized",
                    "-Xshareclasses:none",
                    "-Xss256k",
                    "-XX:ActiveProcessorCount=1",
                    "-Xgc:threads=1",
                    "-XX:-TransparentHugePage",
                    "-Dreactor.runtime.profile=low-rss",
                    "-Dreactor.rust.jni.workers=2",
                    "-Dreactor.rust.jni.queue-capacity=512",
                    "-Dreactor.rust.json.writer-initial-bytes=4096",
                    "-Dreactor.rust.json.writer-retain-max-bytes=65536",
                    "-Dreactor.rust.http.max-connections=1024",
                    "-Dreactor.rust.http.max-inflight-body-bytes=16777216",
                    "-Dreactor.rust.http.max-inflight-response-bytes=16777216",
                    "-Dreactor.rust.http.http1-only-enabled=true",
                    "-Dreactor.rust.runtime.worker-threads=2",
                    "-Dreactor.rust.runtime.max-blocking-threads=4",
                    "-Dreactor.rust.runtime.thread-stack-bytes=262144",
                    "-Dreactor.rust.file-stream.chunk-bytes=65536",
                    "-Dreactor.rust.static-file.inline-max-bytes=524288",
                    "-Dreactor.rust.static-file.max-concurrent-streams=128",
                    "-Dreactor.rust.response-pool.small-capacity=64",
                    "-Dreactor.rust.response-pool.medium-capacity=64",
                    "-Dreactor.rust.response-pool.large-capacity=2",
                    "-Dreactor.rust.response-pool.huge-capacity=1",
                    "-Dreactor.rust.native-cache.max-entries=256",
                    "-Dreactor.rust.native-cache.max-bytes=4194304",
                    "-Dfile.encoding=UTF-8",
                    "-Djava.security.egd=file:/dev/./urandom"
                )
                SpringJavaOpts = Join-JavaOptions -Parts @(
                    "-Xms64m",
                    "-Xmx256m",
                    "-Xquickstart",
                    "-Xtune:virtualized",
                    "-Xshareclasses:none",
                    "-Dserver.port=8080",
                    "-Dlogging.level.root=WARN",
                    "-Dfile.encoding=UTF-8",
                    "-Djava.security.egd=file:/dev/./urandom"
                )
            }
        }
        "balanced" {
            return [PSCustomObject]@{
                Description = "moderate heap/queue budgets for stable p99 without throughput-sized RSS"
                FrameworkMemory = "192m"
                SpringMemory = "512m"
                FrameworkJavaOpts = Join-JavaOptions -Parts @(
                    "-Xms16m",
                    "-Xmx96m",
                    "-Xquickstart",
                    "-Xtune:virtualized",
                    "-Xshareclasses:none",
                    "-Dreactor.rust.jni.workers=0",
                    "-Dreactor.rust.jni.queue-capacity=2048",
                    "-Dreactor.rust.json.writer-initial-bytes=8192",
                    "-Dreactor.rust.json.writer-retain-max-bytes=524288",
                    "-Dreactor.rust.http.max-connections=2048",
                    "-Dreactor.rust.http.max-inflight-body-bytes=67108864",
                    "-Dreactor.rust.http.max-inflight-response-bytes=134217728",
                    "-Dreactor.rust.http.http1-only-enabled=false",
                    "-Dreactor.rust.file-stream.chunk-bytes=131072",
                    "-Dreactor.rust.static-file.inline-max-bytes=1048576",
                    "-Dreactor.rust.static-file.max-concurrent-streams=512",
                    "-Dreactor.rust.response-pool.small-capacity=384",
                    "-Dreactor.rust.response-pool.medium-capacity=768",
                    "-Dreactor.rust.response-pool.large-capacity=64",
                    "-Dreactor.rust.response-pool.huge-capacity=8",
                    "-Dreactor.rust.native-cache.max-entries=2048",
                    "-Dreactor.rust.native-cache.max-bytes=33554432",
                    "-Dfile.encoding=UTF-8",
                    "-Djava.security.egd=file:/dev/./urandom"
                )
                SpringJavaOpts = Join-JavaOptions -Parts @(
                    "-Xms64m",
                    "-Xmx256m",
                    "-Xquickstart",
                    "-Xtune:virtualized",
                    "-Xshareclasses:none",
                    "-Dserver.port=8080",
                    "-Dlogging.level.root=WARN",
                    "-Dfile.encoding=UTF-8",
                    "-Djava.security.egd=file:/dev/./urandom"
                )
            }
        }
        "throughput" {
            return [PSCustomObject]@{
                Description = "larger bounded queues/buffers for peak throughput checks"
                FrameworkMemory = "256m"
                SpringMemory = "512m"
                FrameworkJavaOpts = Join-JavaOptions -Parts @(
                    "-Xms32m",
                    "-Xmx128m",
                    "-Xquickstart",
                    "-Xtune:virtualized",
                    "-Xshareclasses:none",
                    "-Dreactor.rust.jni.workers=0",
                    "-Dreactor.rust.jni.queue-capacity=4096",
                    "-Dreactor.rust.json.writer-initial-bytes=8192",
                    "-Dreactor.rust.json.writer-retain-max-bytes=1048576",
                    "-Dreactor.rust.http.max-connections=4096",
                    "-Dreactor.rust.http.max-inflight-body-bytes=134217728",
                    "-Dreactor.rust.http.max-inflight-response-bytes=268435456",
                    "-Dreactor.rust.http.http1-only-enabled=false",
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
                SpringJavaOpts = Join-JavaOptions -Parts @(
                    "-Xms64m",
                    "-Xmx256m",
                    "-Xquickstart",
                    "-Xtune:virtualized",
                    "-Xshareclasses:none",
                    "-Dserver.port=8080",
                    "-Dlogging.level.root=WARN",
                    "-Dfile.encoding=UTF-8",
                    "-Djava.security.egd=file:/dev/./urandom"
                )
            }
        }
        default {
            throw "Unknown runtime profile: $Profile"
        }
    }
}

$profileConfig = Get-RuntimeProfileConfig -Profile $RuntimeProfile
$frameworkPresetOptions = Get-FrameworkJvmPresetOptions -Preset $FrameworkJvmPreset
if (-not [string]::IsNullOrWhiteSpace($frameworkPresetOptions)) {
    $profileConfig.FrameworkJavaOpts = Join-JavaOptions -Parts @(
        $profileConfig.FrameworkJavaOpts,
        $frameworkPresetOptions
    )
}
$frameworkExtraOptions = New-Object 'System.Collections.Generic.List[string]'
if ($FrameworkCodeCacheMaxRAMPercentage -gt 0) {
    $frameworkExtraOptions.Add("-XX:codecachetotalMaxRAMPercentage=$FrameworkCodeCacheMaxRAMPercentage")
}
if (-not [string]::IsNullOrWhiteSpace($FrameworkCodeCacheTotal)) {
    $frameworkExtraOptions.Add("-Xcodecachetotal$FrameworkCodeCacheTotal")
}
if (-not [string]::IsNullOrWhiteSpace($FrameworkJavaOptsAppend)) {
    $frameworkExtraOptions.Add($FrameworkJavaOptsAppend)
}
$frameworkExtraOptionsValue = Join-JavaOptions -Parts ([string[]] $frameworkExtraOptions)
if (-not [string]::IsNullOrWhiteSpace($frameworkExtraOptionsValue)) {
    $profileConfig.FrameworkJavaOpts = Join-JavaOptions -Parts @(
        $profileConfig.FrameworkJavaOpts,
        $frameworkExtraOptionsValue
    )
}
if ([string]::IsNullOrWhiteSpace($FrameworkMemory)) {
    $FrameworkMemory = $profileConfig.FrameworkMemory
}
if ([string]::IsNullOrWhiteSpace($SpringMemory)) {
    $SpringMemory = $profileConfig.SpringMemory
}

function Invoke-Checked {
    param(
        [string] $FilePath,
        [string[]] $Arguments,
        [string] $WorkingDirectory
    )

    Push-Location $WorkingDirectory
    try {
        & $FilePath @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Command failed: $FilePath $($Arguments -join ' ')"
        }
    } finally {
        Pop-Location
    }
}

function Invoke-Docker {
    param(
        [string[]] $Arguments,
        [string] $WorkingDirectory = $FrameworkRoot
    )
    Invoke-Checked -FilePath "docker" -Arguments $Arguments -WorkingDirectory $WorkingDirectory
}

function Remove-ContainerIfExists {
    param([string] $Name)
    $exists = & docker ps -a --format "{{.Names}}" | Where-Object { $_ -eq $Name }
    if ($exists) {
        & docker rm -f $Name *> $null
    }
}

function Ensure-Network {
    $exists = & docker network ls --format "{{.Name}}" | Where-Object { $_ -eq $NetworkName }
    if (-not $exists) {
        Invoke-Docker -Arguments @("network", "create", $NetworkName)
    }
}

function Test-ContainerExists {
    param([string] $Name)
    $exists = & docker ps -a --format "{{.Names}}" | Where-Object { $_ -eq $Name }
    return [bool] $exists
}

function Find-FrameworkJar {
    $jar = Get-ChildItem -Path (Join-Path $FrameworkRoot "sample\target") -Filter "rust-java-rest-*-sample.jar" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $jar) {
        throw "Framework executable sample jar not found. Build core, then run mvn package in sample/."
    }
    return "sample/target/$($jar.Name)"
}

function Find-SpringJar {
    $jar = Get-ChildItem -Path (Join-Path $SpringRoot "target") -Filter "com.divit.spring-boot-simple-rest-api-*.jar" |
        Where-Object { $_.Name -notmatch "\.original$" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $jar) {
        throw "Spring Boot jar not found. Run mvn package first."
    }
    return "target/$($jar.Name)"
}

function Convert-ToMiB {
    param([string] $Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $null
    }
    $v = $Value.Trim()
    if ($v -notmatch "^([0-9]+(?:\.[0-9]+)?)([KMGTP]?i?B|B)$") {
        return $null
    }

    $number = [double] $Matches[1]
    $unit = $Matches[2]
    switch ($unit) {
        "B"   { return [math]::Round($number / 1024 / 1024, 2) }
        "KiB" { return [math]::Round($number / 1024, 2) }
        "MiB" { return [math]::Round($number, 2) }
        "GiB" { return [math]::Round($number * 1024, 2) }
        "TiB" { return [math]::Round($number * 1024 * 1024, 2) }
        "KB"  { return [math]::Round($number * 1000 / 1024 / 1024, 2) }
        "MB"  { return [math]::Round($number * 1000 * 1000 / 1024 / 1024, 2) }
        "GB"  { return [math]::Round($number * 1000 * 1000 * 1000 / 1024 / 1024, 2) }
        default { return $null }
    }
}

function Get-ProcessRssMiB {
    param([string] $Container)
    $status = & docker exec $Container sh -c "cat /proc/1/status" 2>$null
    if ($LASTEXITCODE -ne 0) {
        return $null
    }

    foreach ($line in $status) {
        if ($line -match "^VmRSS:\s+([0-9]+)\s+kB") {
            return [math]::Round(([double] $Matches[1]) / 1024, 2)
        }
    }
    return $null
}

function Get-ContainerMemoryMiB {
    param([string] $Container)
    $raw = & docker stats $Container --no-stream --format "{{.MemUsage}}" 2>$null
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($raw)) {
        return $null
    }
    $used = ($raw -split "/")[0].Trim()
    return Convert-ToMiB $used
}

function Start-MemorySampler {
    param(
        [string] $Container,
        [string] $OutputFile
    )

    "timestamp,container,container_mem_mib,cpu_percent" | Set-Content -Path $OutputFile -Encoding UTF8
    Start-Job -ArgumentList $Container, $OutputFile -ScriptBlock {
        param($ContainerName, $File)

        function Convert-LocalToMiB {
            param([string] $Value)
            if ([string]::IsNullOrWhiteSpace($Value)) { return "" }
            $v = $Value.Trim()
            if ($v -notmatch "^([0-9]+(?:\.[0-9]+)?)([KMGTP]?i?B|B)$") { return "" }
            $number = [double] $Matches[1]
            $unit = $Matches[2]
            switch ($unit) {
                "B"   { return [math]::Round($number / 1024 / 1024, 2) }
                "KiB" { return [math]::Round($number / 1024, 2) }
                "MiB" { return [math]::Round($number, 2) }
                "GiB" { return [math]::Round($number * 1024, 2) }
                "TiB" { return [math]::Round($number * 1024 * 1024, 2) }
                "KB"  { return [math]::Round($number * 1000 / 1024 / 1024, 2) }
                "MB"  { return [math]::Round($number * 1000 * 1000 / 1024 / 1024, 2) }
                "GB"  { return [math]::Round($number * 1000 * 1000 * 1000 / 1024 / 1024, 2) }
                default { return "" }
            }
        }

        while ($true) {
            $ts = Get-Date -Format o
            $row = & docker stats $ContainerName --no-stream --format "{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}" 2>$null
            if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($row)) {
                $parts = $row -split "\|"
                if ($parts.Length -ge 3) {
                    $name = $parts[0]
                    $cpu = $parts[1].TrimEnd("%")
                    $memText = (($parts[2] -split "/")[0]).Trim()
                    $memMiB = Convert-LocalToMiB $memText
                    Add-Content -Path $File -Value "$ts,$name,$memMiB,$cpu"
                }
            }
            Start-Sleep -Seconds 1
        }
    }
}

function Stop-MemorySampler {
    param([System.Management.Automation.Job] $Job)
    Stop-Job $Job -ErrorAction SilentlyContinue
    Receive-Job $Job -ErrorAction SilentlyContinue | Out-Null
    Remove-Job $Job -ErrorAction SilentlyContinue
}

function Get-MaxSampledMemoryMiB {
    param([string] $CsvFile)
    if (-not (Test-Path $CsvFile)) {
        return $null
    }
    $rows = Import-Csv $CsvFile
    if ($null -eq $rows -or $rows.Count -eq 0) {
        return $null
    }
    $values = $rows |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_.container_mem_mib) } |
        ForEach-Object { [double] $_.container_mem_mib }
    if ($null -eq $values) {
        return $null
    }
    return [math]::Round(($values | Measure-Object -Maximum).Maximum, 2)
}

function Get-CurlCommand {
    $curlExe = Get-Command "curl.exe" -ErrorAction SilentlyContinue
    if ($null -ne $curlExe) {
        return $curlExe.Source
    }

    $curl = Get-Command "curl" -CommandType Application -ErrorAction SilentlyContinue
    if ($null -ne $curl) {
        return $curl.Source
    }

    throw "curl executable is required for benchmark health checks."
}

function Get-NullOutputPath {
    if ($null -ne (Get-Command "curl.exe" -ErrorAction SilentlyContinue)) {
        return "NUL"
    }
    return "/dev/null"
}

function Invoke-LocalHttpStatus {
    param(
        [string] $Url,
        [int] $TimeoutSeconds = 2
    )

    $curl = Get-CurlCommand
    $nullOutput = Get-NullOutputPath
    $status = & $curl -s -o $nullOutput -w "%{http_code}" --max-time $TimeoutSeconds $Url 2>$null
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($status)) {
        return "ERR"
    }
    return "$status".Trim()
}

function Invoke-LocalHttpGet {
    param(
        [string] $Url,
        [int] $TimeoutSeconds = 5
    )

    $curl = Get-CurlCommand
    $output = & $curl -fsS --max-time $TimeoutSeconds $Url 2>$null
    if ($LASTEXITCODE -ne 0) {
        return $null
    }
    return ($output -join "`n")
}

function Wait-Http {
    param(
        [string] $Name,
        [string] $Url,
        [string] $Container
    )

    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    for ($i = 0; $i -lt 90; $i++) {
        $status = Invoke-HttpStatusProbe -Method "GET" -Url $Url
        if ($status -match "^[0-9]{3}$") {
            $code = [int] $status
            if ($code -ge 200 -and $code -lt 300) {
                return [int64]$stopwatch.Elapsed.TotalMilliseconds
            }
        }
        Start-Sleep -Milliseconds 100
    }

    & docker logs $Container *> (Join-Path $ResultsDir "$Container.startup.log")
    throw "$Name did not become reachable at $Url"
}

function Save-StartupDiagnostics {
    param([int64] $ReachableMs)

    $jsonPath = Join-Path $ResultsDir "rust_java_startup.json"
    try {
        $raw = Invoke-RunnerCurl "http://rust-java-rest:8080/diagnostics/startup"
        $raw | Set-Content -Path $jsonPath -Encoding UTF8
        $startup = $raw | ConvertFrom-Json
        return [PSCustomObject]@{
            ReadyMs = [int64]$startup.ready_ms
            ReachableMs = $ReachableMs
        }
    } catch {
        "startup diagnostics scrape failed: $($_.Exception.Message)" |
                Set-Content -Path $jsonPath -Encoding UTF8
        return [PSCustomObject]@{
            ReadyMs = -1
            ReachableMs = $ReachableMs
        }
    }
}

function Parse-LoadProbeOutput {
    param([string] $File)
    $text = Get-Content -Raw -Path $File

    $rps = "N/A"
    $avg = "N/A"
    $p50 = "N/A"
    $p90 = "N/A"
    $p99 = "N/A"
    $errors = ""
    $httpStatus = ""

    if ($text -match "Requests/sec:\s+([0-9.]+)") { $rps = $Matches[1] }
    if ($text -match "(?m)^\s*Latency\s+([0-9.]+[a-z]+)") { $avg = $Matches[1] }
    if ($text -match "(?m)^\s*50%\s+([0-9.]+[a-z]+)") { $p50 = $Matches[1] }
    if ($text -match "(?m)^\s*90%\s+([0-9.]+[a-z]+)") { $p90 = $Matches[1] }
    if ($text -match "(?m)^\s*99%\s+([0-9.]+[a-z]+)") { $p99 = $Matches[1] }
    if ($text -match "Socket errors:\s*(.+)") { $errors = $Matches[1].Trim() }
    if ([string]::IsNullOrWhiteSpace($errors) -and $text -match "(?mi)^\s*errors total:\s+([0-9]+)") {
        if ([int64] $Matches[1] -gt 0) {
            $errors = "total=$($Matches[1])"
        }
    }
    $statusMatches = [regex]::Matches($text, "(?m)^Status\s+([0-9]{3}):\s+([0-9]+)")
    if ($statusMatches.Count -gt 0) {
        $parts = foreach ($match in $statusMatches) {
            "$($match.Groups[1].Value)=$($match.Groups[2].Value)"
        }
        $httpStatus = $parts -join ", "
    }

    [PSCustomObject]@{
        Rps = $rps
        Avg = $avg
        P50 = $p50
        P90 = $p90
        P99 = $p99
        Errors = $errors
        HttpStatus = $httpStatus
    }
}

function Convert-ToDoubleOrNull {
    param([object] $Value)

    if ($null -eq $Value) {
        return $null
    }
    $text = "$Value"
    if ([string]::IsNullOrWhiteSpace($text) -or $text -eq "N/A") {
        return $null
    }

    $parsed = 0.0
    $style = [System.Globalization.NumberStyles]::Float
    $culture = [System.Globalization.CultureInfo]::InvariantCulture
    if ([double]::TryParse($text, $style, $culture, [ref] $parsed)) {
        return $parsed
    }
    return $null
}

function Invoke-HttpStatusProbe {
    param(
        [string] $Method,
        [string] $Url
    )

    $args = @(
        "run", "--rm",
        "--network", $NetworkName,
        "--entrypoint", "curl",
        "-v", "$ResultsDir`:/results",
        $RunnerImage,
        "-s",
        "-o", "/dev/null",
        "-w", "%{http_code}",
        "-X", $Method
    )
    if ($Method -eq "POST") {
        $args += @("-H", "Content-Type: application/json", "--data-binary", "@/results/post_body.json")
    }
    $args += @($Url)

    $status = & docker @args 2>$null
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($status)) {
        return "ERR"
    }
    return "$status".Trim()
}

function Get-BaseUrl {
    param([string] $Url)
    $uri = [System.Uri] $Url
    $port = if ($uri.IsDefaultPort) { "" } else { ":$($uri.Port)" }
    return "$($uri.Scheme)://$($uri.Host)$port"
}

function Invoke-RunnerCurl {
    param([string] $Url)
    $args = @(
        "run", "--rm",
        "--network", $NetworkName,
        "--entrypoint", "curl",
        $RunnerImage,
        "-fsS",
        $Url
    )
    $output = & docker @args 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "Required framework probe failed: $Url"
    }
    return ($output -join "`n")
}

function Invoke-PlanPreWarmCase {
    param(
        [string] $Target,
        [string] $EndpointName,
        [string] $Method,
        [string] $Url,
        [int] $Concurrency
    )

    $bodyFile = if ($Method -eq "POST") { "/results/post_body.json" } else { "" }
    $args = @(
        "run", "--rm",
        "--network", $NetworkName,
        "--entrypoint", "load-probe",
        "-v", "$ResultsDir`:/results",
        $RunnerImage,
        "--url", $Url,
        "--method", $Method,
        "--concurrency", "$Concurrency",
        "--duration", $PlanPreWarmDuration,
        "--timeout-ms", "10000"
    )
    if (-not [string]::IsNullOrWhiteSpace($bodyFile)) {
        $args += @("--body-file", $bodyFile)
    }

    & docker @args *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Plan pre-warm failed for $Target $EndpointName $Method concurrency=$Concurrency"
    }
}

function Invoke-LoadProbe {
    param(
        [string] $Target,
        [string] $EndpointName,
        [string] $EndpointClass,
        [string] $Method,
        [string] $Container,
        [string] $Url,
        [int] $Concurrency,
        [int] $RunId,
        [string] $LuaScript = ""
    )

    $safeEndpoint = $EndpointName -replace "[^A-Za-z0-9_-]", "_"
    $prefix = "$Target`_$safeEndpoint`_$Method`_c$Concurrency`_r$RunId"
    $outputFile = Join-Path $ResultsDir "$prefix.txt"
    $memoryFile = Join-Path $ResultsDir "$prefix.memory.csv"
    $metricsFile = Join-Path $ResultsDir "$prefix.metrics.prom"
    $baseUrl = Get-BaseUrl $Url

    $rssBefore = Get-ProcessRssMiB $Container
    $memBefore = Get-ContainerMemoryMiB $Container
    $statusBefore = Invoke-HttpStatusProbe -Method $Method -Url $Url
    $bodyFile = if ($Method -eq "POST") { "/results/post_body.json" } else { "" }

    $sampler = Start-MemorySampler -Container $Container -OutputFile $memoryFile
    try {
        $warmupArgs = @(
            "run", "--rm",
            "--network", $NetworkName,
            "--entrypoint", "load-probe",
            "-v", "$ResultsDir`:/results",
            $RunnerImage,
            "--url", $Url,
            "--method", $Method,
            "--concurrency", "$Concurrency",
            "--duration", $Warmup,
            "--timeout-ms", "10000"
        )
        if (-not [string]::IsNullOrWhiteSpace($bodyFile)) {
            $warmupArgs += @("--body-file", $bodyFile)
        }
        & docker @warmupArgs *> $null

        if ($Target -eq "rust_java") {
            Invoke-RunnerCurl "$baseUrl/metrics/reset" | Out-Null
        }

        $args = @(
            "run", "--rm",
            "--network", $NetworkName,
            "--entrypoint", "load-probe",
            "-v", "$ResultsDir`:/results",
            $RunnerImage,
            "--url", $Url,
            "--method", $Method,
            "--concurrency", "$Concurrency",
            "--duration", $Duration,
            "--timeout-ms", "10000"
        )
        if (-not [string]::IsNullOrWhiteSpace($bodyFile)) {
            $args += @("--body-file", $bodyFile)
        }

        $output = & docker @args 2>&1
        $exitCode = $LASTEXITCODE
        $output | Set-Content -Path $outputFile -Encoding UTF8
        if ($exitCode -ne 0) {
            throw "load-probe failed for $prefix. See $outputFile"
        }
    } finally {
        Stop-MemorySampler $sampler
    }

    $rssAfter = Get-ProcessRssMiB $Container
    $memAfter = Get-ContainerMemoryMiB $Container
    $maxMem = Get-MaxSampledMemoryMiB $memoryFile
    if ($null -eq $maxMem) {
        $sampleFallback = @($memBefore, $memAfter) | Where-Object { $null -ne $_ }
        if ($sampleFallback.Count -gt 0) {
            $maxMem = [math]::Round(($sampleFallback | Measure-Object -Maximum).Maximum, 2)
        }
    }
    $parsed = Parse-LoadProbeOutput $outputFile
    $statusAfter = Invoke-HttpStatusProbe -Method $Method -Url $Url
    if ($Target -eq "rust_java") {
        $metrics = Invoke-RunnerCurl "$baseUrl/metrics"
        if ($null -ne $metrics) {
            $metrics | Set-Content -Path $metricsFile -Encoding UTF8
        }
    }
    $httpStatus = if ([string]::IsNullOrWhiteSpace($parsed.HttpStatus)) {
        "probe=$statusBefore/$statusAfter"
    } else {
        $parsed.HttpStatus
    }

    [PSCustomObject]@{
        Target = $Target
        Endpoint = $EndpointName
        EndpointClass = $EndpointClass
        Method = $Method
        Concurrency = $Concurrency
        Run = $RunId
        Rps = $parsed.Rps
        Avg = $parsed.Avg
        P50 = $parsed.P50
        P90 = $parsed.P90
        P99 = $parsed.P99
        Errors = $parsed.Errors
        HttpStatus = $httpStatus
        RssBeforeMiB = $rssBefore
        RssAfterMiB = $rssAfter
        MemBeforeMiB = $memBefore
        MemAfterMiB = $memAfter
        MaxContainerMemMiB = $maxMem
        RawFile = $outputFile
        MemoryFile = $memoryFile
        MetricsFile = if (Test-Path $metricsFile) { $metricsFile } else { "" }
    }
}

function Write-Summary {
    param([object[]] $Rows)

    $summary = Join-Path $ResultsDir "summary.md"
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("# Rust-Java REST vs Spring Boot Container Benchmark")
    $lines.Add("")
    $lines.Add("- Date: $(Get-Date -Format o)")
    $lines.Add("- JVM runtime: ibm-semeru-runtimes:open-21-jre-jammy")
    $lines.Add("- runtime profile: $RuntimeProfile ($($profileConfig.Description))")
    $lines.Add("- framework JVM preset: $FrameworkJvmPreset")
    $lines.Add("- CPU limit per service: $CpuLimit")
    $lines.Add("- Framework memory limit: $FrameworkMemory")
    $lines.Add("- Spring memory limit: $SpringMemory")
    $lines.Add("- Framework JAVA_OPTS: ``$($profileConfig.FrameworkJavaOpts)``")
    $lines.Add("- Framework JAVA_TOOL_OPTIONS: ``$FrameworkJavaToolOptions``")
    if ($FrameworkOnly) {
        $lines.Add("- Spring target: skipped (`-FrameworkOnly`)")
    } else {
        $lines.Add("- Spring JAVA_OPTS: ``$($profileConfig.SpringJavaOpts)``")
    }
    $lines.Add("- load probe duration: $Duration")
    $lines.Add("- load probe warmup: $Warmup")
    $lines.Add("- plan pre-warm: $PlanPreWarm")
    if ($PlanPreWarm) {
        $lines.Add("- plan pre-warm duration: $PlanPreWarmDuration")
    }
    $lines.Add("- concurrency levels: $($ConcurrencyValues -join ', ')")
    $lines.Add("- repeat count: $RepeatCount")
    $lines.Add("- randomized order: $RandomizeOrder")
    $lines.Add("- results CSV: $(Join-Path $ResultsDir "results.csv")")
    if ($EndpointClassFilter.Count -gt 0) {
        $lines.Add("- endpoint class filter: $($EndpointClassFilter -join ', ')")
    }
    if ($RandomSeed -gt 0) {
        $lines.Add("- random seed: $RandomSeed")
    }
    $lines.Add("")
    $lines.Add("| Run | Target | Class | Endpoint | Method | C | RPS | Avg | P50 | P90 | P99 | HTTP Status | Errors | RSS Before MiB | RSS After MiB | Max Container Mem MiB |")
    $lines.Add("|---:|---|---|---|---:|---:|---:|---:|---:|---:|---:|---|---|---:|---:|---:|")

    foreach ($row in $Rows) {
        $errors = if ([string]::IsNullOrWhiteSpace($row.Errors)) { "" } else { $row.Errors.Replace("|", "/") }
        $httpStatus = if ([string]::IsNullOrWhiteSpace($row.HttpStatus)) { "" } else { $row.HttpStatus.Replace("|", "/") }
        $lines.Add("| $($row.Run) | $($row.Target) | $($row.EndpointClass) | $($row.Endpoint) | $($row.Method) | $($row.Concurrency) | $($row.Rps) | $($row.Avg) | $($row.P50) | $($row.P90) | $($row.P99) | $httpStatus | $errors | $($row.RssBeforeMiB) | $($row.RssAfterMiB) | $($row.MaxContainerMemMiB) |")
    }

    foreach ($class in @(
        "small-json",
        "annotated-generated-json",
        "dynamic-producer-json",
        "dynamic-dto-json",
        "direct-json-writer",
        "producer-json",
        "rust-json-writer",
        "raw-json",
        "native-cache-json",
        "file-static",
        "file-stream",
        "file-stream-large"
    )) {
        $classRows = @($Rows | Where-Object { $_.EndpointClass -eq $class } | Sort-Object Run, Target, Endpoint, Concurrency)
        if ($classRows.Count -eq 0) {
            continue
        }

        $lines.Add("")
        $lines.Add("## $class")
        $lines.Add("")
        $lines.Add("| Run | Target | Endpoint | C | RPS | P99 | RSS After MiB | Max Container Mem MiB |")
        $lines.Add("|---:|---|---|---:|---:|---:|---:|---:|")
        foreach ($row in $classRows) {
            $lines.Add("| $($row.Run) | $($row.Target) | $($row.Endpoint) | $($row.Concurrency) | $($row.Rps) | $($row.P99) | $($row.RssAfterMiB) | $($row.MaxContainerMemMiB) |")
        }
    }

    $lines.Add("")
    $lines.Add("## Rust/Spring RPS Ratio")
    $lines.Add("")
    $lines.Add("| Run | Class | Endpoint | C | Rust RPS | Spring RPS | RPS Ratio | Rust P99 | Spring P99 | Rust Max Mem MiB | Spring Max Mem MiB |")
    $lines.Add("|---:|---|---|---:|---:|---:|---:|---:|---:|---:|---:|")

    $groups = $Rows | Group-Object { "$($_.Run)|$($_.EndpointClass)|$($_.Endpoint)|$($_.Concurrency)" } | Sort-Object Name
    foreach ($group in $groups) {
        $rust = @($group.Group | Where-Object { $_.Target -eq "rust_java" } | Select-Object -First 1)
        $spring = @($group.Group | Where-Object { $_.Target -eq "spring_boot" } | Select-Object -First 1)
        if ($rust.Count -eq 0 -or $spring.Count -eq 0) {
            continue
        }

        $rustRps = Convert-ToDoubleOrNull $rust[0].Rps
        $springRps = Convert-ToDoubleOrNull $spring[0].Rps
        $ratio = "N/A"
        if ($null -ne $rustRps -and $null -ne $springRps -and $springRps -gt 0) {
            $ratio = [math]::Round($rustRps / $springRps, 2)
        }

        $lines.Add("| $($rust[0].Run) | $($rust[0].EndpointClass) | $($rust[0].Endpoint) | $($rust[0].Concurrency) | $($rust[0].Rps) | $($spring[0].Rps) | $ratio | $($rust[0].P99) | $($spring[0].P99) | $($rust[0].MaxContainerMemMiB) | $($spring[0].MaxContainerMemMiB) |")
    }

    $lines | Set-Content -Path $summary -Encoding UTF8
    return $summary
}

function Save-ResultCheckpoint {
    param([object[]] $Rows)

    if ($Rows.Count -eq 0) {
        return
    }

    $resultsCsv = Join-Path $ResultsDir "results.csv"
    $temporaryCsv = "$resultsCsv.tmp"
    $Rows | Export-Csv -NoTypeInformation -Encoding UTF8 -Path $temporaryCsv
    Move-Item -LiteralPath $temporaryCsv -Destination $resultsCsv -Force
}

function Save-RouteDiagnostics {
    $jsonPath = Join-Path $ResultsDir "rust_java_routes.json"
    $summaryPath = Join-Path $ResultsDir "rust_java_routes_summary.md"
    try {
        $raw = Invoke-RunnerCurl "http://rust-java-rest:8080/diagnostics/routes"
    } catch {
        $raw = $null
    }
    if ($null -eq $raw) {
        "route diagnostics scrape failed" | Set-Content -Path $jsonPath -Encoding UTF8
        "route diagnostics scrape failed" | Set-Content -Path $summaryPath -Encoding UTF8
        return
    }

    $raw | Set-Content -Path $jsonPath -Encoding UTF8
    try {
        $diag = $raw | ConvertFrom-Json
        $lines = New-Object System.Collections.Generic.List[string]
        $lines.Add("# Rust-Java Route Diagnostics")
        $lines.Add("")
        $lines.Add("- total routes: $($diag.total)")
        $lines.Add("- production routes: $($diag.production_routes)")
        $lines.Add("- benchmark-only routes: $($diag.benchmark_only)")
        $lines.Add("- production legacy routes: $($diag.production_legacy)")
        $lines.Add("- benchmark legacy routes: $($diag.benchmark_legacy)")
        $lines.Add("- production heavy JSON object graph routes: $($diag.heavy_json_object_graph)")
        $lines.Add("- benchmark heavy JSON object graph routes: $($diag.benchmark_heavy_json_object_graph)")
        $lines.Add("")
        $lines.Add("| Method | Path | Workload | Budget | Strategy | Benchmark Only | Production Route | Heavy JSON Object Graph |")
        $lines.Add("|---|---|---|---|---|---:|---:|---:|")
        foreach ($route in @($diag.routes)) {
            $lines.Add("| $($route.method) | $($route.path) | $($route.workload) | $($route.workload_budget) | $($route.strategy) | $($route.benchmark_only) | $($route.production_route) | $($route.heavy_json_object_graph) |")
        }
        $lines | Set-Content -Path $summaryPath -Encoding UTF8
    } catch {
        "route diagnostics parse failed: $($_.Exception.Message)" | Set-Content -Path $summaryPath -Encoding UTF8
    }
}

$getLua = Join-Path $ResultsDir "get_status.lua"
@'
local statuses = {}

response = function(status, headers, body)
  statuses[status] = (statuses[status] or 0) + 1
end

done = function(summary, latency, requests)
  for code, count in pairs(statuses) do
    io.write(string.format("Status %d: %d\n", code, count))
  end
end
'@ | Set-Content -Path $getLua -Encoding ASCII

$postBody = Join-Path $ResultsDir "post_body.json"
@'
{"orderId":"ORD-1001","amount":350.75,"paid":true,"address":{"city":"Ankara","street":"Ataturk Cd."},"customer":{"name":"mustafa customer a.s","email":"mustafa@gmai.com"},"items":[{"name":"test0","price":12.89},{"name":"test1","price":13.89},{"name":"test2","price":14.89}]}
'@ | Set-Content -Path $postBody -Encoding ASCII

$postLua = Join-Path $ResultsDir "post_echo.lua"
@'
local statuses = {}

wrk.method = "POST"
wrk.headers["Content-Type"] = "application/json"
wrk.body = [[{"orderId":"ORD-1001","amount":350.75,"paid":true,"address":{"city":"Ankara","street":"Ataturk Cd."},"customer":{"name":"mustafa customer a.s","email":"mustafa@gmai.com"},"items":[{"name":"test0","price":12.89},{"name":"test1","price":13.89},{"name":"test2","price":14.89}]}]]

response = function(status, headers, body)
  statuses[status] = (statuses[status] or 0) + 1
end

done = function(summary, latency, requests)
  for code, count in pairs(statuses) do
    io.write(string.format("Status %d: %d\n", code, count))
  end
end
'@ | Set-Content -Path $postLua -Encoding ASCII

$rows = New-Object System.Collections.Generic.List[object]

try {
    if (-not $SkipBuild) {
        Invoke-Checked -FilePath "mvn" -Arguments @("-q", "clean", "install", "-DskipTests") -WorkingDirectory $FrameworkRoot
        Invoke-Checked -FilePath "mvn" -Arguments @("-q", "clean", "package", "-DskipTests") -WorkingDirectory (Join-Path $FrameworkRoot "sample")
        if (-not $FrameworkOnly) {
            Invoke-Checked -FilePath "mvn" -Arguments @("-q", "clean", "package", "-DskipTests") -WorkingDirectory $SpringRoot
        }
    }

    if (-not $SkipImageBuild) {
        $frameworkJar = Find-FrameworkJar
        Invoke-Docker -Arguments @("build", "-t", $FrameworkImage, "-f", "benchmark/docker/framework.Dockerfile", "--build-arg", "JAR_FILE=$frameworkJar", ".") -WorkingDirectory $FrameworkRoot
        if (-not $FrameworkOnly) {
            $springJar = Find-SpringJar
            Invoke-Docker -Arguments @("build", "-t", $SpringImage, "-f", "Dockerfile.benchmark", "--build-arg", "JAR_FILE=$springJar", ".") -WorkingDirectory $SpringRoot
        }
    } else {
        Invoke-Checked -FilePath "docker" -Arguments @("image", "inspect", $FrameworkImage) -WorkingDirectory $FrameworkRoot | Out-Null
        if (-not $FrameworkOnly) {
            Invoke-Checked -FilePath "docker" -Arguments @("image", "inspect", $SpringImage) -WorkingDirectory $SpringRoot | Out-Null
        }
    }
    if (-not $SkipRunnerImageBuild) {
        Invoke-Docker -Arguments @("build", "-t", $RunnerImage, "-f", "Dockerfile.benchmark", ".") -WorkingDirectory $ScriptDir
    } else {
        Invoke-Checked -FilePath "docker" -Arguments @("image", "inspect", $RunnerImage) -WorkingDirectory $ScriptDir | Out-Null
    }

    Ensure-Network
    Remove-ContainerIfExists $FrameworkContainer
    if (-not $FrameworkOnly) {
        Remove-ContainerIfExists $SpringContainer
    }

    $frameworkJavaOpts = $profileConfig.FrameworkJavaOpts
    $springJavaOpts = $profileConfig.SpringJavaOpts

    Invoke-Docker -Arguments @(
        "run", "-d",
        "--name", $FrameworkContainer,
        "--network", $NetworkName,
        "-p", "8080:8080",
        "--cpus", "$CpuLimit",
        "--memory", $FrameworkMemory,
        "-e", "JAVA_TOOL_OPTIONS=$FrameworkJavaToolOptions",
        "-e", "JAVA_OPTS=$frameworkJavaOpts",
        $FrameworkImage
    )
    if (-not $FrameworkOnly) {
        Invoke-Docker -Arguments @(
            "run", "-d",
            "--name", $SpringContainer,
            "--network", $NetworkName,
            "-p", "8081:8080",
            "--cpus", "$CpuLimit",
            "--memory", $SpringMemory,
            "-e", "JAVA_OPTS=$springJavaOpts",
            $SpringImage
        )
    }

    $frameworkReachableMs = Wait-Http -Name "Rust-Java framework" -Url "http://rust-java-rest:8080/api/v1/candidates" -Container $FrameworkContainer
    $frameworkStartup = Save-StartupDiagnostics -ReachableMs $frameworkReachableMs
    if (-not $FrameworkOnly) {
        [void](Wait-Http -Name "Spring Boot" -Url "http://spring-boot-rest:8080/api/v1/candidates" -Container $SpringContainer)
    }

    $endpoints = @(
        [PSCustomObject]@{
            Name = "candidates"
            Class = "small-json-legacy"
            Method = "GET"
            RustPath = "/api/v1/candidates"
            SpringPath = "/api/v1/candidates"
            Targets = @("rust_java", "spring_boot")
            Lua = "/results/get_status.lua"
        },
        [PSCustomObject]@{
            Name = "candidates_direct_bodyless"
            Class = "small-json-direct"
            Method = "GET"
            RustPath = "/api/v1/candidates/direct"
            SpringPath = ""
            Targets = @("rust_java")
            Lua = "/results/get_status.lua"
        },
        [PSCustomObject]@{
            Name = "users_search_generated"
            Class = "annotated-generated-json"
            Method = "GET"
            RustPath = "/users/search?name=load&page=1"
            SpringPath = ""
            Targets = @("rust_java")
            Lua = "/results/get_status.lua"
        },
        [PSCustomObject]@{
            Name = "echo_parse_business"
            Class = "echo-parse"
            Method = "POST"
            RustPath = "/api/v1/echo"
            SpringPath = "/api/v1/echo"
            Targets = @("rust_java", "spring_boot")
            Lua = "/results/post_echo.lua"
        },
        [PSCustomObject]@{
            Name = "echo_raw_copy"
            Class = "echo-raw"
            Method = "POST"
            RustPath = "/api/v1/echo/raw"
            SpringPath = ""
            Targets = @("rust_java")
            Lua = "/results/post_echo.lua"
        },
        [PSCustomObject]@{
            Name = "heavy100_dynamic_producer"
            Class = "dynamic-producer-json"
            Method = "GET"
            RustPath = "/api/v1/heavy/dto?items=100"
            SpringPath = ""
            Targets = @("rust_java")
            Lua = "/results/get_status.lua"
        },
        [PSCustomObject]@{
            Name = "heavy100_dynamic_producer_async"
            Class = "dynamic-producer-json-async"
            Method = "GET"
            RustPath = "/api/v1/heavy/dto/async?items=100"
            SpringPath = ""
            Targets = @("rust_java")
            Lua = "/results/get_status.lua"
        },
        [PSCustomObject]@{
            Name = "heavy100_dynamic_dto_legacy"
            Class = "dynamic-dto-json"
            Method = "GET"
            RustPath = "/api/v1/heavy/dto/legacy?items=100"
            SpringPath = "/api/v1/heavy?items=100"
            Targets = @("rust_java", "spring_boot")
            Lua = "/results/get_status.lua"
        },
        [PSCustomObject]@{
            Name = "heavy100_direct_writer"
            Class = "direct-json-writer"
            Method = "GET"
            RustPath = "/api/v1/heavy?items=100"
            SpringPath = ""
            Targets = @("rust_java")
            Lua = "/results/get_status.lua"
        },
        [PSCustomObject]@{
            Name = "heavy100_producer_json"
            Class = "producer-json"
            Method = "GET"
            RustPath = "/api/v1/heavy/producer?items=100"
            SpringPath = ""
            Targets = @("rust_java")
            Lua = "/results/get_status.lua"
        },
        [PSCustomObject]@{
            Name = "heavy100_producer_json_async"
            Class = "producer-json-async"
            Method = "GET"
            RustPath = "/api/v1/heavy/producer/async?items=100"
            SpringPath = ""
            Targets = @("rust_java")
            Lua = "/results/get_status.lua"
        },
        [PSCustomObject]@{
            Name = "heavy100_rust_writer"
            Class = "rust-json-writer"
            Method = "GET"
            RustPath = "/api/v1/heavy/rust?items=100"
            SpringPath = ""
            Targets = @("rust_java")
            Lua = "/results/get_status.lua"
        },
        [PSCustomObject]@{
            Name = "heavy100_raw"
            Class = "raw-json"
            Method = "GET"
            RustPath = "/api/v1/heavy/raw"
            SpringPath = "/api/v1/heavy/raw"
            Targets = @("rust_java", "spring_boot")
            Lua = "/results/get_status.lua"
        },
        [PSCustomObject]@{
            Name = "heavy100_native_cache"
            Class = "native-cache-json"
            Method = "GET"
            RustPath = "/api/v1/heavy/cache?items=100"
            SpringPath = ""
            Targets = @("rust_java")
            Lua = "/results/get_status.lua"
        },
        [PSCustomObject]@{
            Name = "export_static_registered"
            Class = "file-static"
            Method = "GET"
            RustPath = "/api/v1/export/static"
            SpringPath = ""
            Targets = @("rust_java")
            Lua = "/results/get_status.lua"
        },
        [PSCustomObject]@{
            Name = "export_file_stream"
            Class = "file-stream"
            Method = "GET"
            RustPath = "/api/v1/export/file"
            SpringPath = ""
            Targets = @("rust_java")
            Lua = "/results/get_status.lua"
        },
        [PSCustomObject]@{
            Name = "export_large_file_stream"
            Class = "file-stream-large"
            Method = "GET"
            RustPath = "/api/v1/export/file-large"
            SpringPath = ""
            Targets = @("rust_java")
            Lua = "/results/get_status.lua"
        }
    )
    if ($RuntimeProfile -eq "micro-rss") {
        $endpoints = @($endpoints | Where-Object { $_.Class -notin @("dynamic-dto-json", "rust-json-writer") })
    }
    if ($EndpointClassFilter.Count -gt 0) {
        $unknownClasses = @($EndpointClassFilter | Where-Object { $_ -notin @($endpoints | ForEach-Object { $_.Class }) })
        if ($unknownClasses.Count -gt 0) {
            throw "Unknown or unavailable endpoint class for selected profile: $($unknownClasses -join ', ')"
        }
        $endpoints = @($endpoints | Where-Object { $_.Class -in $EndpointClassFilter })
    }
    $targets = @(
        [PSCustomObject]@{ Name = "rust_java"; Container = $FrameworkContainer; BaseUrl = "http://rust-java-rest:8080" }
    )
    if (-not $FrameworkOnly) {
        $targets += [PSCustomObject]@{ Name = "spring_boot"; Container = $SpringContainer; BaseUrl = "http://spring-boot-rest:8080" }
    }

    $testPlan = New-Object System.Collections.Generic.List[object]
    foreach ($target in $targets) {
        foreach ($concurrency in $ConcurrencyValues) {
            foreach ($endpoint in $endpoints) {
                if ($endpoint.Targets -notcontains $target.Name) {
                    continue
                }
                $path = if ($target.Name -eq "rust_java") { $endpoint.RustPath } else { $endpoint.SpringPath }
                if ([string]::IsNullOrWhiteSpace($path)) {
                    continue
                }
                $testPlan.Add([PSCustomObject]@{
                    Target = $target
                    Concurrency = $concurrency
                    Endpoint = $endpoint
                    Path = $path
                })
            }
        }
    }

    if ($PlanPreWarm) {
        Write-Host "Running plan pre-warm duration=$PlanPreWarmDuration"
        foreach ($case in ($testPlan.ToArray() | Sort-Object { $_.Target.Name }, { $_.Endpoint.Name }, Concurrency)) {
            Write-Host "Pre-warm $($case.Target.Name) $($case.Endpoint.Name) $($case.Endpoint.Method) concurrency=$($case.Concurrency)"
            Invoke-PlanPreWarmCase `
                -Target $case.Target.Name `
                -EndpointName $case.Endpoint.Name `
                -Method $case.Endpoint.Method `
                -Url "$($case.Target.BaseUrl)$($case.Path)" `
                -Concurrency $case.Concurrency
        }
        Invoke-RunnerCurl "http://rust-java-rest:8080/metrics/reset" | Out-Null
    }

    for ($run = 1; $run -le $RepeatCount; $run++) {
        $cases = if ($RandomizeOrder) {
            @($testPlan | Sort-Object { Get-Random })
        } else {
            $testPlan.ToArray()
        }

        foreach ($case in $cases) {
            $target = $case.Target
            $endpoint = $case.Endpoint
            $concurrency = $case.Concurrency
            Write-Host "Benchmark run=$run $($target.Name) $($endpoint.Name) $($endpoint.Method) concurrency=$concurrency"
            $row = Invoke-LoadProbe `
                -Target $target.Name `
                -EndpointName $endpoint.Name `
                -EndpointClass $endpoint.Class `
                -Method $endpoint.Method `
                -Container $target.Container `
                -Url "$($target.BaseUrl)$($case.Path)" `
                -Concurrency $concurrency `
                -RunId $run `
                -LuaScript $endpoint.Lua
            $startupReadyMs = ""
            $startupReachableMs = ""
            if ($target.Name -eq "rust_java") {
                $startupReadyMs = $frameworkStartup.ReadyMs
                $startupReachableMs = $frameworkStartup.ReachableMs
            }
            $row | Add-Member -NotePropertyName StartupReadyMs -NotePropertyValue $startupReadyMs
            $row | Add-Member -NotePropertyName StartupReachableMs -NotePropertyValue $startupReachableMs
            $rows.Add($row)
            Save-ResultCheckpoint -Rows $rows.ToArray()
        }
    }

    try {
        $finalMetrics = Invoke-RunnerCurl "http://rust-java-rest:8080/metrics"
    } catch {
        $finalMetrics = $null
    }
    if ($null -ne $finalMetrics) {
        $finalMetrics | Set-Content -Path (Join-Path $ResultsDir "rust_java_metrics.prom") -Encoding UTF8
    } else {
        "metrics scrape failed" | Set-Content -Path (Join-Path $ResultsDir "rust_java_metrics.prom") -Encoding UTF8
    }
    Save-RouteDiagnostics

    Save-ResultCheckpoint -Rows $rows.ToArray()
    $summary = Write-Summary -Rows $rows.ToArray()
    Write-Host "Benchmark complete: $summary"
} finally {
    if (Test-ContainerExists $FrameworkContainer) {
        & docker logs $FrameworkContainer *> (Join-Path $ResultsDir "$FrameworkContainer.log")
    }
    if (-not $FrameworkOnly -and (Test-ContainerExists $SpringContainer)) {
        & docker logs $SpringContainer *> (Join-Path $ResultsDir "$SpringContainer.log")
    }

    if (-not $KeepContainers) {
        Remove-ContainerIfExists $FrameworkContainer
        if (-not $FrameworkOnly) {
            Remove-ContainerIfExists $SpringContainer
        }
    }
}
