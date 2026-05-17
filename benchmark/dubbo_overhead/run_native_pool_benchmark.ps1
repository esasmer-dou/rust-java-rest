param(
    [object] $PoolSizes = @(4, 16, 32, 64),
    [object] $ConcurrencyValues = @(64, 256, 512, 1000),
    [int] $DurationSeconds = 6,
    [int] $WarmupSeconds = 2,
    [int] $IdleSeconds = 3,
    [int] $AppPort = 18082,
    [int] $ProviderPort = 20880,
    [int] $JniWorkers = 16,
    [switch] $SkipBuild,
    [switch] $KeepProcesses
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$FrameworkRoot = Resolve-Path (Join-Path $ScriptDir "..\..")
$WorkspaceRoot = Resolve-Path (Join-Path $FrameworkRoot "..")
$ProviderRoot = Join-Path $WorkspaceRoot "dubbo-sample-provider"
$ResultsDir = Join-Path $ScriptDir ("results\native_pool_{0}" -f (Get-Date -Format "yyyyMMdd_HHmmss"))
$LoadRunner = Join-Path $ScriptDir "load_runner.js"

function Get-MavenProjectVersion {
    param([string] $PomPath)
    [xml] $pom = Get-Content -Raw -Path $PomPath
    $version = [string] $pom.project.version
    if ([string]::IsNullOrWhiteSpace($version) -and $pom.project.parent) {
        $version = [string] $pom.project.parent.version
    }
    if ([string]::IsNullOrWhiteSpace($version)) {
        throw "Cannot resolve Maven project version from $PomPath"
    }
    return $version.Trim()
}

$FrameworkVersion = Get-MavenProjectVersion (Join-Path $FrameworkRoot "pom.xml")
$ProviderVersion = Get-MavenProjectVersion (Join-Path $ProviderRoot "pom.xml")
$AppJar = Join-Path $FrameworkRoot "target\rust-java-rest-$FrameworkVersion.jar"
$ProviderJar = Join-Path $ProviderRoot "target\dubbo-sample-provider-$ProviderVersion.jar"

New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null

function Convert-List {
    param([object] $Value)
    if ($Value -is [array]) {
        return @($Value | ForEach-Object { [int] $_ })
    }
    return @("$Value" -split "[,\s]+" | Where-Object { $_ } | ForEach-Object { [int] $_ })
}

$PoolList = Convert-List $PoolSizes
$ConcurrencyList = Convert-List $ConcurrencyValues

function Test-Zookeeper {
    try {
        $client = [System.Net.Sockets.TcpClient]::new()
        $iar = $client.BeginConnect("127.0.0.1", 2181, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne(1500)) {
            $client.Close()
            return $false
        }
        $client.EndConnect($iar)
        $stream = $client.GetStream()
        $bytes = [Text.Encoding]::ASCII.GetBytes("ruok")
        $stream.Write($bytes, 0, $bytes.Length)
        Start-Sleep -Milliseconds 150
        $buffer = New-Object byte[] 16
        $read = $stream.Read($buffer, 0, $buffer.Length)
        $client.Close()
        return ([Text.Encoding]::ASCII.GetString($buffer, 0, $read) -eq "imok")
    } catch {
        return $false
    }
}

function Ensure-Zookeeper {
    if (Test-Zookeeper) {
        return
    }
    Push-Location $WorkspaceRoot
    try {
        & docker compose -f dubbo-dev-compose.yml up -d | Out-Null
    } finally {
        Pop-Location
    }
    for ($i = 0; $i -lt 25; $i++) {
        if (Test-Zookeeper) {
            return
        }
        Start-Sleep -Seconds 1
    }
    throw "Zookeeper is not reachable at 127.0.0.1:2181"
}

function Wait-Port {
    param([int] $Port, [int] $TimeoutSeconds = 30)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue) {
            return
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Port $Port did not open"
}

function Wait-Http {
    param([string] $Url, [int] $TimeoutSeconds = 40)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 2 | Out-Null
            return
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    throw "HTTP readiness failed: $Url"
}

function Get-ProcMemory {
    param([int] $ProcessId)
    $p = Get-Process -Id $ProcessId -ErrorAction Stop
    return [PSCustomObject]@{
        working_set_mb = [Math]::Round($p.WorkingSet64 / 1MB, 2)
        private_mb = [Math]::Round($p.PrivateMemorySize64 / 1MB, 2)
        threads = $p.Threads.Count
        cpu_sec = [Math]::Round($p.TotalProcessorTime.TotalSeconds, 3)
    }
}

function Get-Diagnostics {
    $content = (Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$AppPort/diagnostics/memory" -TimeoutSec 5).Content
    return $content | ConvertFrom-Json
}

function Start-Provider {
    $out = Join-Path $ResultsDir "provider.out.log"
    $err = Join-Path $ResultsDir "provider.err.log"
    $args = @(
        "-Ddubbo.provider.port=$ProviderPort",
        "-Ddubbo.provider.host=127.0.0.1",
        "-Ddubbo.provider.bind-host=127.0.0.1",
        "-jar",
        $ProviderJar
    )
    $process = Start-Process -FilePath "java" -ArgumentList $args -WorkingDirectory $ProviderRoot `
        -RedirectStandardOutput $out -RedirectStandardError $err -WindowStyle Hidden -PassThru
    Wait-Port -Port $ProviderPort -TimeoutSeconds 30
    return $process
}

function Start-App {
    param([int] $PoolSize)
    $name = "pool_$PoolSize"
    $out = Join-Path $ResultsDir "$name.app.out.log"
    $err = Join-Path $ResultsDir "$name.app.err.log"
    $args = @(
        "-Xms8m",
        "-Xmx48m",
        "-Xss256k",
        "-Xquickstart",
        "-Xtune:virtualized",
        "-Xshareclasses:none",
        "-Dserver.port=$AppPort",
        "-Dreactor.dubbo.enabled=true",
        "-Dreactor.dubbo.transport=native",
        "-Dreactor.dubbo.native-connections-per-endpoint=$PoolSize",
        "-Dreactor.dubbo.max-inflight=512",
        "-Dreactor.rust.jni.workers=$JniWorkers",
        "-Dreactor.rust.jni.queue-capacity=1024",
        "-Dreactor.rust.http.max-connections=1200",
        "-Dreactor.rust.http.http1-only-enabled=true",
        "-Dreactor.rust.runtime.worker-threads=2",
        "-Dreactor.rust.runtime.max-blocking-threads=4",
        "-Dreactor.rust.runtime.thread-stack-bytes=262144",
        "-Dreactor.rust.log.level=error",
        "-Dreactor.rust.java.log.level=warn",
        "-Dfile.encoding=UTF-8",
        "-Djava.security.egd=file:/dev/./urandom",
        "-jar",
        $AppJar
    )
    $process = Start-Process -FilePath "java" -ArgumentList $args -WorkingDirectory $FrameworkRoot `
        -RedirectStandardOutput $out -RedirectStandardError $err -WindowStyle Hidden -PassThru
    Wait-Http -Url "http://127.0.0.1:$AppPort/api/v1/dubbo/catalog" -TimeoutSeconds 45
    return $process
}

function Run-Load {
    param([int] $PoolSize, [int] $Concurrency, [int] $Duration)
    $url = "http://127.0.0.1:$AppPort/api/v1/dubbo/catalog"
    $raw = & node $LoadRunner --url $url --concurrency $Concurrency --duration-sec $Duration --timeout-ms 10000
    $raw | Set-Content -Path (Join-Path $ResultsDir "pool_${PoolSize}_c${Concurrency}.load.json") -Encoding UTF8
    return $raw | ConvertFrom-Json
}

function Stop-ProcessSafe {
    param($Process)
    if ($null -ne $Process) {
        Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
    }
}

$results = New-Object System.Collections.Generic.List[object]
$provider = $null
$app = $null

try {
    if (-not $SkipBuild) {
        & mvn -q install -f (Join-Path $WorkspaceRoot "dubbo-sample-api\pom.xml")
        & mvn -q install -f (Join-Path $WorkspaceRoot "java-rust-dubbo\pom.xml")
        & mvn -q package -f (Join-Path $ProviderRoot "pom.xml")
        & mvn -q package -DskipTests -f (Join-Path $FrameworkRoot "pom.xml")
    }

    Ensure-Zookeeper
    $provider = Start-Provider

    foreach ($poolSize in $PoolList) {
        $app = Start-App -PoolSize $poolSize
        Start-Sleep -Seconds 2
        $startupMemory = Get-ProcMemory -ProcessId $app.Id
        Run-Load -PoolSize $poolSize -Concurrency 16 -Duration $WarmupSeconds | Out-Null
        $warmupMemory = Get-ProcMemory -ProcessId $app.Id

        foreach ($concurrency in $ConcurrencyList) {
            Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$AppPort/metrics/reset" -TimeoutSec 5 | Out-Null
            $beforeMemory = Get-ProcMemory -ProcessId $app.Id
            $load = Run-Load -PoolSize $poolSize -Concurrency $concurrency -Duration $DurationSeconds
            $afterMemory = Get-ProcMemory -ProcessId $app.Id
            Start-Sleep -Seconds $IdleSeconds
            $idleMemory = Get-ProcMemory -ProcessId $app.Id
            $diagnostics = Get-Diagnostics
            ($diagnostics | ConvertTo-Json -Depth 8) | Set-Content -Path (Join-Path $ResultsDir "pool_${poolSize}_c${concurrency}.diagnostics.json") -Encoding UTF8
            $nativeDubbo = $diagnostics.native_dubbo

            $results.Add([PSCustomObject]@{
                pool_size = $poolSize
                concurrency = $concurrency
                requests = $load.requests
                errors = $load.errors_total
                statuses = $load.statuses
                rps = [Math]::Round($load.rps, 2)
                avg_ms = [Math]::Round($load.latency_us.avg / 1000.0, 3)
                p50_ms = [Math]::Round($load.latency_us.p50 / 1000.0, 3)
                p95_ms = [Math]::Round($load.latency_us.p95 / 1000.0, 3)
                p99_ms = [Math]::Round($load.latency_us.p99 / 1000.0, 3)
                max_ms = [Math]::Round($load.latency_us.max / 1000.0, 3)
                ws_startup_mb = $startupMemory.working_set_mb
                ws_warmup_mb = $warmupMemory.working_set_mb
                ws_before_mb = $beforeMemory.working_set_mb
                ws_after_mb = $afterMemory.working_set_mb
                ws_idle_mb = $idleMemory.working_set_mb
                private_idle_mb = $idleMemory.private_mb
                threads_after = $afterMemory.threads
                native_calls = $nativeDubbo.nativeDubboCalls
                native_open_connections = $nativeDubbo.nativeDubboOpenConnections
                native_idle_connections = $nativeDubbo.nativeDubboIdleConnections
                native_opened = $nativeDubbo.nativeDubboConnectionsOpened
                native_reused = $nativeDubbo.nativeDubboConnectionsReused
                native_closed = $nativeDubbo.nativeDubboConnectionsClosed
                native_pool_exhausted = $nativeDubbo.nativeDubboPoolExhausted
                native_errors = $nativeDubbo.nativeDubboErrors
                native_timeouts = $nativeDubbo.nativeDubboTimeouts
                native_rejected = $nativeDubbo.nativeDubboRejected
                native_avg_us = $nativeDubbo.nativeDubboAvgLatencyUs
            })
        }

        Stop-ProcessSafe $app
        $app = $null
        Start-Sleep -Seconds 3
    }
} finally {
    if (-not $KeepProcesses) {
        Stop-ProcessSafe $app
        Stop-ProcessSafe $provider
    }
}

$jsonPath = Join-Path $ResultsDir "summary.json"
$results | ConvertTo-Json -Depth 8 | Set-Content -Path $jsonPath -Encoding UTF8

$summary = New-Object System.Text.StringBuilder
[void]$summary.AppendLine("# Native Dubbo Pool Benchmark")
[void]$summary.AppendLine()
[void]$summary.AppendLine("- Endpoint: /api/v1/dubbo/catalog")
[void]$summary.AppendLine("- Duration per run: ${DurationSeconds}s")
[void]$summary.AppendLine("- Warmup: ${WarmupSeconds}s")
[void]$summary.AppendLine("- JNI workers: $JniWorkers")
[void]$summary.AppendLine("- App port: $AppPort")
[void]$summary.AppendLine("- Results: $ResultsDir")
[void]$summary.AppendLine()
[void]$summary.AppendLine("| pool | c | rps | avg ms | p95 ms | p99 ms | errors | WS idle MB | open conn | idle conn | opened | reused | pool exhausted | native avg us |")
[void]$summary.AppendLine("|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
foreach ($row in $results) {
    [void]$summary.AppendLine("| $($row.pool_size) | $($row.concurrency) | $($row.rps) | $($row.avg_ms) | $($row.p95_ms) | $($row.p99_ms) | $($row.errors) | $($row.ws_idle_mb) | $($row.native_open_connections) | $($row.native_idle_connections) | $($row.native_opened) | $($row.native_reused) | $($row.native_pool_exhausted) | $($row.native_avg_us) |")
}

$summaryPath = Join-Path $ResultsDir "summary.md"
$summary.ToString() | Set-Content -Path $summaryPath -Encoding UTF8

Write-Output "summary: $summaryPath"
Write-Output "json: $jsonPath"
