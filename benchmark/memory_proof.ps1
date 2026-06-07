param(
    [ValidateSet("micro-rest", "micro-rest-plus", "micro-rss", "ultra-low-rss", "low-rss", "throughput")]
    [string] $RuntimeProfile = "ultra-low-rss",
    [int[]] $ConcurrencyValues = @(64, 256, 512, 1000),
    [string[]] $Endpoints = @(
        "/api/v1/heavy?items=100",
        "/api/v1/heavy/dto?items=100",
        "/api/v1/heavy/dto/legacy?items=100",
        "/api/v1/heavy/rust?items=100",
        "/api/v1/heavy/cache?items=100",
        "/api/v1/heavy/raw"
    ),
    [int] $DurationSeconds = 20,
    [int] $IdleSeconds = 30,
    [int] $FinalIdleSeconds = 60,
    [int] $HostPort = 18082,
    [string] $ResultsDir = "",
    [switch] $SkipBuild,
    [switch] $TrimAfterRun
)

$ErrorActionPreference = "Stop"
if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$FrameworkRoot = Resolve-Path (Join-Path $ScriptDir "..")
$Image = "rust-java-rest:benchmark"
$RunnerImage = "reactor-benchmark-runner:local"
$Container = "rust-java-memory-proof"
if ([string]::IsNullOrWhiteSpace($ResultsDir)) {
    $ResultsDir = Join-Path $ScriptDir ("results\memory_proof_{0}_{1}" -f $RuntimeProfile, (Get-Date -Format "yyyyMMdd_HHmmss"))
}
$ResultsDir = [System.IO.Path]::GetFullPath($ResultsDir)
$HostBaseUrl = "http://127.0.0.1:$HostPort"

if ($RuntimeProfile -eq "micro-rss" -and -not $PSBoundParameters.ContainsKey("Endpoints")) {
    $Endpoints = @(
        "/api/v1/heavy/rust?items=100",
        "/api/v1/heavy/cache?items=100",
        "/api/v1/heavy/raw"
    )
}

function Join-JavaOptions {
    param([string[]] $Parts)
    return (($Parts | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join " ")
}

function Get-ProfileOpts {
    switch ($RuntimeProfile) {
        "micro-rest" {
            return [PSCustomObject]@{
                Memory = "80m"
                JavaOpts = Join-JavaOptions @(
                    "-Xms8m", "-Xmx40m", "-Xss256k", "-Xquickstart", "-Xtune:virtualized", "-Xshareclasses:none",
                    "-XX:ActiveProcessorCount=1", "-Xgc:threads=1", "-XX:-TransparentHugePage",
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
                    "-Xms8m", "-Xmx40m", "-Xss256k", "-Xquickstart", "-Xtune:virtualized", "-Xshareclasses:none",
                    "-XX:ActiveProcessorCount=1", "-Xgc:threads=1", "-XX:-TransparentHugePage",
                    "-Dreactor.runtime.profile=micro-rest-plus",
                    "-Dreactor.rust.log.level=error",
                    "-Dreactor.rust.java.log.level=warn",
                    "-Dfile.encoding=UTF-8",
                    "-Djava.security.egd=file:/dev/./urandom"
                )
            }
        }
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
                Memory = "96m"
                JavaOpts = Join-JavaOptions @(
                    "-Xms8m", "-Xmx40m", "-Xss256k", "-Xquickstart", "-Xtune:virtualized", "-Xshareclasses:none",
                    "-XX:ActiveProcessorCount=1", "-Xgc:threads=1", "-XX:-TransparentHugePage",
                    "-Dreactor.runtime.profile=low-rss",
                    "-Dreactor.rust.jni.workers=2",
                    "-Dreactor.rust.jni.queue-capacity=512",
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
            }
        }
    }
}

function Find-FrameworkSampleJar {
    $jar = Get-ChildItem -Path (Join-Path $FrameworkRoot "target") -Filter "rust-java-rest-*-sample.jar" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $jar) {
        throw "Framework executable sample jar not found. Run mvn package first."
    }
    return "target/$($jar.Name)"
}

function Ensure-FrameworkRuntimeDependencies {
    $dependencyDir = Join-Path $FrameworkRoot "target\dependency"
    $hasRuntimeDeps = (Test-Path $dependencyDir) -and
        $null -ne (Get-ChildItem -Path $dependencyDir -Filter "*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1)

    if ($hasRuntimeDeps) {
        return
    }

    Write-Host "Framework runtime dependency directory is missing; copying runtime dependencies for sample benchmark image."
    & mvn -q -DskipTests -f (Join-Path $FrameworkRoot "pom.xml") dependency:copy-dependencies "-DincludeScope=runtime" "-DoutputDirectory=target/dependency"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to copy framework runtime dependencies."
    }
}

function Save-Diagnostics {
    param([string] $Name)
    $path = Join-Path $ResultsDir "$Name.json"
    (Invoke-WebRequest -UseBasicParsing "$HostBaseUrl/diagnostics/memory" -TimeoutSec 10).Content |
        Set-Content -Path $path -Encoding UTF8
}

function Run-Probe {
    param([string] $Endpoint, [int] $Concurrency)
    $safeName = ($Endpoint -replace '[^a-zA-Z0-9]+', '_').Trim('_')
    $out = Join-Path $ResultsDir ("load_{0}_c{1}.txt" -f $safeName, $Concurrency)
    docker run --rm --network "container:$Container" --entrypoint load-probe $RunnerImage `
        --url "http://127.0.0.1:8080$Endpoint" `
        --concurrency $Concurrency `
        --duration "${DurationSeconds}s" `
        --timeout-ms 10000 | Tee-Object -FilePath $out
}

function Remove-ContainerIfExists {
    param([string] $Name)

    $existing = @(& docker ps -a --format "{{.Names}}" | Where-Object { $_ -eq $Name })
    if ($existing.Count -gt 0) {
        & docker rm -f $Name | Out-Null
    }
}

function Get-PrometheusCounter {
    param([string] $Prometheus, [string] $Name)

    $match = [regex]::Match($Prometheus, "$([regex]::Escape($Name))\s+([0-9]+)")
    if ($match.Success) {
        return [int64] $match.Groups[1].Value
    }
    return 0
}

function Write-DiagnosticsSummary {
    $summaryRows = @(
        Get-ChildItem -Path $ResultsDir -Filter "*.json" |
            Sort-Object Name |
            ForEach-Object {
                $json = Get-Content -Raw -Path $_.FullName | ConvertFrom-Json
                $directPool = $json.buffer_pools | Where-Object { $_.name -eq "direct" } | Select-Object -First 1
                [PSCustomObject]@{
                    phase = $_.BaseName
                    rss_mib = [Math]::Round($json.native.smaps_rollup.rss_kb / 1024.0, 2)
                    vmrss_mib = [Math]::Round($json.native.process.vm_rss_kb / 1024.0, 2)
                    heap_used_mib = [Math]::Round($json.jvm.heap_used_bytes / 1048576.0, 2)
                    non_heap_used_mib = [Math]::Round($json.jvm.non_heap_used_bytes / 1048576.0, 2)
                    direct_buffer_mib = if ($null -ne $directPool) { [Math]::Round($directPool.memory_used_bytes / 1048576.0, 2) } else { 0 }
                    threads = $json.native.process.threads
                    route_rejected = $json.native.route_admission.rejected
                    jni_queue_full = Get-PrometheusCounter -Prometheus $json.native_metrics_prometheus -Name "reactor_native_jni_queue_full_total"
                }
            }
    )

    $csv = Join-Path $ResultsDir "memory_proof_summary.csv"
    $summaryRows | Export-Csv -Path $csv -NoTypeInformation -Encoding UTF8

    $baseline = $summaryRows | Where-Object { $_.phase -eq "00_baseline" } | Select-Object -First 1
    $final = $summaryRows | Where-Object { $_.phase -like "99_final_idle_*" } | Select-Object -First 1
    $peak = $summaryRows | Sort-Object rss_mib -Descending | Select-Object -First 1
    $maxIdle = $summaryRows | Where-Object { $_.phase -like "idle_*" } | Sort-Object rss_mib -Descending | Select-Object -First 1

    $md = Join-Path $ResultsDir "memory_proof_summary.md"
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("# Memory Proof Summary")
    $lines.Add("")
    $lines.Add(("- Runtime profile: ``{0}``" -f $RuntimeProfile))
    $lines.Add(("- Duration seconds: ``{0}``, idle seconds: ``{1}``, final idle seconds: ``{2}``" -f $DurationSeconds, $IdleSeconds, $FinalIdleSeconds))
    $lines.Add("")
    $lines.Add("| Metric | Value |")
    $lines.Add("|---|---:|")
    if ($null -ne $baseline) {
        $lines.Add(("| Baseline RSS MiB | {0:N2} |" -f $baseline.rss_mib))
    }
    if ($null -ne $final) {
        $lines.Add(("| Final idle RSS MiB | {0:N2} |" -f $final.rss_mib))
        if ($null -ne $baseline) {
            $lines.Add(("| Final minus baseline RSS MiB | {0:N2} |" -f ($final.rss_mib - $baseline.rss_mib)))
        }
    }
    if ($null -ne $peak) {
        $lines.Add(("| Peak RSS MiB | {0:N2} |" -f $peak.rss_mib))
        $lines.Add(("| Peak phase | ``{0}`` |" -f $peak.phase))
    }
    if ($null -ne $maxIdle) {
        $lines.Add(("| Max idle RSS MiB | {0:N2} |" -f $maxIdle.rss_mib))
        $lines.Add(("| Max idle phase | ``{0}`` |" -f $maxIdle.phase))
    }
    $lines | Set-Content -Path $md -Encoding UTF8
}

New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null
$profile = Get-ProfileOpts

try {
    if (-not $SkipBuild) {
        & mvn -q -DskipTests -f (Join-Path $FrameworkRoot "pom.xml") package
        $frameworkJar = Find-FrameworkSampleJar
        Ensure-FrameworkRuntimeDependencies
        docker build -t $Image `
            -f (Join-Path $FrameworkRoot "benchmark/docker/framework.Dockerfile") `
            --build-arg "JAR_FILE=$frameworkJar" `
            $FrameworkRoot
        docker build -q -t $RunnerImage -f (Join-Path $ScriptDir "Dockerfile.benchmark") $ScriptDir | Out-Null
    } else {
        Ensure-FrameworkRuntimeDependencies
    }

    Remove-ContainerIfExists -Name $Container
    $containerId = docker run -d --name $Container --memory $profile.Memory -p "${HostPort}:8080" -e "JAVA_OPTS=$($profile.JavaOpts)" $Image
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($containerId)) {
        throw "Failed to start $Container on host port $HostPort."
    }

    $ready = $false
    for ($i = 0; $i -lt 30; $i++) {
        try {
            Invoke-WebRequest -UseBasicParsing "$HostBaseUrl/health" -TimeoutSec 2 | Out-Null
            $ready = $true
            break
        } catch {
            Start-Sleep -Seconds 1
        }
    }
    if (-not $ready) {
        docker logs $Container *> (Join-Path $ResultsDir "$Container.startup.log")
        throw "$Container did not become healthy on $HostBaseUrl."
    }

    Save-Diagnostics "00_baseline"
    Invoke-WebRequest -UseBasicParsing "$HostBaseUrl/metrics/reset" -TimeoutSec 10 | Out-Null
    foreach ($endpoint in $Endpoints) {
        Invoke-WebRequest -UseBasicParsing "$HostBaseUrl$endpoint" -TimeoutSec 10 | Out-Null
    }
    Save-Diagnostics "01_warmup"

    foreach ($concurrency in $ConcurrencyValues) {
        foreach ($endpoint in $Endpoints) {
            Run-Probe -Endpoint $endpoint -Concurrency $concurrency
            $safeName = ($endpoint -replace '[^a-zA-Z0-9]+', '_').Trim('_')
            Save-Diagnostics ("after_{0}_c{1}" -f $safeName, $concurrency)
            Start-Sleep -Seconds $IdleSeconds
            Save-Diagnostics ("idle_{0}_c{1}" -f $safeName, $concurrency)
            if ($TrimAfterRun) {
                Invoke-WebRequest -UseBasicParsing "$HostBaseUrl/diagnostics/native/trim" -TimeoutSec 10 |
                    Select-Object -ExpandProperty Content |
                    Set-Content -Path (Join-Path $ResultsDir ("trim_{0}_c{1}.json" -f $safeName, $concurrency)) -Encoding UTF8
            }
        }
    }
    if ($FinalIdleSeconds -gt 0) {
        Start-Sleep -Seconds $FinalIdleSeconds
        Save-Diagnostics ("99_final_idle_{0}s" -f $FinalIdleSeconds)
    }
} finally {
    Remove-ContainerIfExists -Name $Container
}

Write-DiagnosticsSummary
Write-Output "memory proof results: $ResultsDir"
