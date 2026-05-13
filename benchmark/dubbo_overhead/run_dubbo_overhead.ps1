param(
    [object] $ConcurrencyValues = @(64, 256, 512, 1000),
    [int] $DurationSeconds = 8,
    [int] $WarmupSeconds = 2,
    [int] $IdleSeconds = 5,
    [int] $AppPort = 18081,
    [int] $ProviderPort = 20880,
    [string] $RuntimeProfile = "low-rss",
    [int] $JniWorkers = 2,
    [int] $JniQueueCapacity = 512,
    [int] $NativeConnectionsPerEndpoint = 2,
    [int] $NativeAsyncWorkers = 2,
    [int] $NativeAsyncQueueCapacity = 128,
    [int] $DubboMaxInflight = 64,
    [bool] $DubboCatalogAdaptiveEnabled = $true,
    [int] $DubboCatalogMinInflight = 0,
    [int] $DubboCatalogInitialInflight = 0,
    [int] $DubboCatalogMaxInflight = 4,
    [int] $DubboCatalogResponseTimeoutMs = 0,
    [int] $DubboCatalogTargetLatencyMs = 0,
    [int] $DubboCatalogHighLatencyMs = 0,
    [int] $DubboCatalogRpcWorkers = 1,
    [int] $DubboCatalogRpcQueueCapacity = 0,
    [string] $DubboProviders = "",
    [bool] $UseZookeeper = $false,
    [switch] $RandomizeRunOrder,
    [switch] $SkipBuild,
    [switch] $KeepProcesses
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$FrameworkRoot = Resolve-Path (Join-Path $ScriptDir "..\..")
$WorkspaceRoot = Resolve-Path (Join-Path $FrameworkRoot "..")
$ProviderRoot = Join-Path $WorkspaceRoot "dubbo-sample-provider"
$ResultsDir = Join-Path $ScriptDir ("results\run_{0}" -f (Get-Date -Format "yyyyMMdd_HHmmss"))
$LoadRunner = Join-Path $ScriptDir "load_runner.js"
$AppJar = Join-Path $FrameworkRoot "target\rust-java-rest-3.1.0-rc1.jar"
$ProviderJar = Join-Path $ProviderRoot "target\dubbo-sample-provider-3.1.0-rc1.jar"
$EffectiveDubboProviders = if ([string]::IsNullOrWhiteSpace($DubboProviders)) {
    "127.0.0.1:$ProviderPort"
} else {
    $DubboProviders.Trim()
}

New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null

if ($ConcurrencyValues -is [array]) {
    $ConcurrencyList = @($ConcurrencyValues | ForEach-Object { [int] $_ })
} else {
    $ConcurrencyList = @(
        "$ConcurrencyValues" -split "[,\s]+" |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            ForEach-Object { [int] $_ }
    )
}
if ($ConcurrencyList.Count -eq 0) {
    throw "At least one concurrency level is required."
}

function Join-JavaOptions {
    param([string[]] $Parts)
    return (($Parts | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join " ")
}

function Get-AppJavaArgs {
    param([bool] $DubboEnabled)
    $enabledText = if ($DubboEnabled) { "true" } else { "false" }
    $javaArgs = @(
        "-Xms8m",
        "-Xmx48m",
        "-Xss256k",
        "-Xquickstart",
        "-Xtune:virtualized",
        "-Xshareclasses:none",
        "-Dserver.port=$AppPort",
        "-Dreactor.runtime.profile=$RuntimeProfile",
        "-Dreactor.dubbo.enabled=$enabledText",
        "-Dreactor.dubbo.transport=native",
        "-Dreactor.dubbo.providers=$EffectiveDubboProviders",
        "-Dreactor.dubbo.native-connections-per-endpoint=$NativeConnectionsPerEndpoint",
        "-Dreactor.dubbo.native-async-workers=$NativeAsyncWorkers",
        "-Dreactor.dubbo.native-async-queue-capacity=$NativeAsyncQueueCapacity",
        "-Dreactor.dubbo.max-inflight=$DubboMaxInflight",
        "-Dreactor.dubbo.catalog.adaptive-enabled=$($DubboCatalogAdaptiveEnabled.ToString().ToLowerInvariant())",
        "-Dreactor.dubbo.catalog.max-inflight=$DubboCatalogMaxInflight",
        "-Dreactor.dubbo.catalog.rpc-workers=$DubboCatalogRpcWorkers",
        "-Dreactor.dubbo.catalog.rpc-queue-capacity=$DubboCatalogRpcQueueCapacity",
        "-Dreactor.rust.jni.workers=$JniWorkers",
        "-Dreactor.rust.jni.queue-capacity=$JniQueueCapacity",
        "-Dreactor.rust.http.max-connections=1200",
        "-Dreactor.rust.http.max-inflight-body-bytes=16777216",
        "-Dreactor.rust.http.max-inflight-response-bytes=33554432",
        "-Dreactor.rust.http.http1-only-enabled=true",
        "-Dreactor.rust.runtime.worker-threads=2",
        "-Dreactor.rust.runtime.max-blocking-threads=4",
        "-Dreactor.rust.runtime.thread-stack-bytes=262144",
        "-Dreactor.rust.response-pool.small-capacity=128",
        "-Dreactor.rust.response-pool.medium-capacity=128",
        "-Dreactor.rust.response-pool.large-capacity=4",
        "-Dreactor.rust.response-pool.huge-capacity=1",
        "-Dreactor.rust.native-cache.max-entries=256",
        "-Dreactor.rust.native-cache.max-bytes=8388608",
        "-Dreactor.rust.log.level=error",
        "-Dreactor.rust.java.log.level=warn",
        "-Dfile.encoding=UTF-8",
        "-Djava.security.egd=file:/dev/./urandom"
    )
    if ($DubboCatalogMinInflight -gt 0) {
        $javaArgs += "-Dreactor.dubbo.catalog.min-inflight=$DubboCatalogMinInflight"
    }
    if ($DubboCatalogInitialInflight -gt 0) {
        $javaArgs += "-Dreactor.dubbo.catalog.initial-inflight=$DubboCatalogInitialInflight"
    }
    if ($DubboCatalogResponseTimeoutMs -gt 0) {
        $javaArgs += "-Dreactor.dubbo.catalog.response-timeout-ms=$DubboCatalogResponseTimeoutMs"
    }
    if ($DubboCatalogTargetLatencyMs -gt 0) {
        $javaArgs += "-Dreactor.dubbo.catalog.target-latency-ms=$DubboCatalogTargetLatencyMs"
    }
    if ($DubboCatalogHighLatencyMs -gt 0) {
        $javaArgs += "-Dreactor.dubbo.catalog.high-latency-ms=$DubboCatalogHighLatencyMs"
    }
    $javaArgs += @("-jar", $AppJar)
    return $javaArgs
}

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
    for ($i = 0; $i -lt 20; $i++) {
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
    param([string] $Url, [int] $TimeoutSeconds = 30)
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
        pid = $p.Id
        working_set_mb = [Math]::Round($p.WorkingSet64 / 1MB, 2)
        private_mb = [Math]::Round($p.PrivateMemorySize64 / 1MB, 2)
        paged_mb = [Math]::Round($p.PagedMemorySize64 / 1MB, 2)
        threads = $p.Threads.Count
        cpu_sec = [Math]::Round($p.TotalProcessorTime.TotalSeconds, 3)
    }
}

function Save-Diagnostics {
    param([string] $Name)
    try {
        $content = (Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$AppPort/diagnostics/memory" -TimeoutSec 5).Content
        $content | Set-Content -Path (Join-Path $ResultsDir "$Name.diagnostics.json") -Encoding UTF8
    } catch {
        "{}" | Set-Content -Path (Join-Path $ResultsDir "$Name.diagnostics.json") -Encoding UTF8
    }
}

function Run-Load {
    param(
        [string] $Scenario,
        [string] $EndpointName,
        [string] $Endpoint,
        [int] $Concurrency,
        [int] $Duration
    )
    $url = "http://127.0.0.1:$AppPort$Endpoint"
    $raw = & node $LoadRunner --url $url --concurrency $Concurrency --duration-sec $Duration --timeout-ms 10000
    $safe = "$Scenario`_$EndpointName`_c$Concurrency"
    $raw | Set-Content -Path (Join-Path $ResultsDir "$safe.load.json") -Encoding UTF8
    return $raw | ConvertFrom-Json
}

function Start-Provider {
    $out = Join-Path $ResultsDir "provider.out.log"
    $err = Join-Path $ResultsDir "provider.err.log"
    $args = @(
        "-Ddubbo.provider.port=$ProviderPort",
        "-Ddubbo.provider.host=127.0.0.1",
        "-Ddubbo.provider.bind-host=127.0.0.1",
        "-Dreactor.dubbo.registry-address=$(if ($UseZookeeper) { 'zookeeper://127.0.0.1:2181' } else { 'none' })",
        "-jar",
        $ProviderJar
    )
    $process = Start-Process -FilePath "java" -ArgumentList $args -WorkingDirectory $ProviderRoot `
        -RedirectStandardOutput $out -RedirectStandardError $err -WindowStyle Hidden -PassThru
    Wait-Port -Port $ProviderPort -TimeoutSeconds 30
    return $process
}

function Start-App {
    param([bool] $DubboEnabled, [string] $Scenario)
    $out = Join-Path $ResultsDir "$Scenario.app.out.log"
    $err = Join-Path $ResultsDir "$Scenario.app.err.log"
    $args = Get-AppJavaArgs -DubboEnabled $DubboEnabled
    $process = Start-Process -FilePath "java" -ArgumentList $args -WorkingDirectory $FrameworkRoot `
        -RedirectStandardOutput $out -RedirectStandardError $err -WindowStyle Hidden -PassThru
    Wait-Http -Url "http://127.0.0.1:$AppPort/api/v1/heavy/raw" -TimeoutSeconds 40
    return $process
}

function Stop-ProcessSafe {
    param($Process)
    if ($null -ne $Process) {
        Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
    }
}

$results = New-Object System.Collections.Generic.List[object]
$memoryRows = New-Object System.Collections.Generic.List[object]
$provider = $null
$app = $null

try {
    if (-not $SkipBuild) {
        & mvn -q install -f (Join-Path $WorkspaceRoot "dubbo-sample-api\pom.xml")
        & mvn -q install -f (Join-Path $WorkspaceRoot "java-rust-dubbo\pom.xml")
        & mvn -q package -f (Join-Path $ProviderRoot "pom.xml")
        & mvn -q package -DskipTests -f (Join-Path $FrameworkRoot "pom.xml")
    }

    if ($UseZookeeper) {
        Ensure-Zookeeper
    }

    $scenarios = @(
        [PSCustomObject]@{
            Name = "baseline_no_dubbo"
            DubboEnabled = $false
            Endpoints = @(
                [PSCustomObject]@{ Name = "raw"; Path = "/api/v1/heavy/raw" },
                [PSCustomObject]@{ Name = "candidates"; Path = "/api/v1/candidates" }
            )
        },
        [PSCustomObject]@{
            Name = "dubbo_enabled"
            DubboEnabled = $true
            Endpoints = @(
                [PSCustomObject]@{ Name = "raw"; Path = "/api/v1/heavy/raw" },
                [PSCustomObject]@{ Name = "candidates"; Path = "/api/v1/candidates" },
                [PSCustomObject]@{ Name = "dubbo_catalog"; Path = "/api/v1/dubbo/catalog" }
            )
        }
    )
    if ($RandomizeRunOrder) {
        $scenarios = @($scenarios | Sort-Object { Get-Random })
    }

    foreach ($scenario in $scenarios) {
        if ($scenario.DubboEnabled) {
            $provider = Start-Provider
        }
        $app = Start-App -DubboEnabled $scenario.DubboEnabled -Scenario $scenario.Name
        Start-Sleep -Seconds 2
        $startupMemory = Get-ProcMemory -ProcessId $app.Id
        $memoryRows.Add([PSCustomObject]@{
            scenario = $scenario.Name
            phase = "startup"
            endpoint = ""
            concurrency = 0
            memory = $startupMemory
        })
        Save-Diagnostics "$($scenario.Name)_startup"

        foreach ($endpoint in $scenario.Endpoints) {
            Run-Load -Scenario $scenario.Name -EndpointName $endpoint.Name -Endpoint $endpoint.Path -Concurrency 16 -Duration $WarmupSeconds | Out-Null
        }
        $warmupMemory = Get-ProcMemory -ProcessId $app.Id
        $memoryRows.Add([PSCustomObject]@{
            scenario = $scenario.Name
            phase = "after_warmup"
            endpoint = ""
            concurrency = 0
            memory = $warmupMemory
        })
        Save-Diagnostics "$($scenario.Name)_after_warmup"

        $workItems = foreach ($concurrency in $ConcurrencyList) {
            foreach ($endpoint in $scenario.Endpoints) {
                [PSCustomObject]@{
                    Concurrency = $concurrency
                    Endpoint = $endpoint
                }
            }
        }
        if ($RandomizeRunOrder) {
            $workItems = @($workItems | Sort-Object { Get-Random })
        }

        foreach ($workItem in $workItems) {
                $concurrency = $workItem.Concurrency
                $endpoint = $workItem.Endpoint
                Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$AppPort/metrics/reset" -TimeoutSec 5 | Out-Null
                $before = Get-ProcMemory -ProcessId $app.Id
                $load = Run-Load -Scenario $scenario.Name -EndpointName $endpoint.Name -Endpoint $endpoint.Path -Concurrency $concurrency -Duration $DurationSeconds
                $after = Get-ProcMemory -ProcessId $app.Id
                Start-Sleep -Seconds $IdleSeconds
                $idle = Get-ProcMemory -ProcessId $app.Id
                Save-Diagnostics "$($scenario.Name)_$($endpoint.Name)_c$concurrency"

                $results.Add([PSCustomObject]@{
                    scenario = $scenario.Name
                    endpoint = $endpoint.Name
                    path = $endpoint.Path
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
                    transfer_mb_s = [Math]::Round($load.transfer_bytes_per_sec / 1MB, 3)
                    working_set_before_mb = $before.working_set_mb
                    working_set_after_mb = $after.working_set_mb
                    working_set_idle_mb = $idle.working_set_mb
                    private_before_mb = $before.private_mb
                    private_after_mb = $after.private_mb
                    private_idle_mb = $idle.private_mb
                    threads_after = $after.threads
                    cpu_sec_after = $after.cpu_sec
                })
        }

        Stop-ProcessSafe $app
        $app = $null
        if ($scenario.DubboEnabled) {
            Stop-ProcessSafe $provider
            $provider = $null
        }
        Start-Sleep -Seconds 3
    }
} finally {
    if (-not $KeepProcesses) {
        Stop-ProcessSafe $app
        Stop-ProcessSafe $provider
    }
}

$resultsPath = Join-Path $ResultsDir "summary.json"
$memoryPath = Join-Path $ResultsDir "memory_phases.json"
$results | ConvertTo-Json -Depth 8 | Set-Content -Path $resultsPath -Encoding UTF8
$memoryRows | ConvertTo-Json -Depth 8 | Set-Content -Path $memoryPath -Encoding UTF8

$summary = New-Object System.Text.StringBuilder
[void]$summary.AppendLine("# Dubbo Consumer Overhead Benchmark")
[void]$summary.AppendLine()
[void]$summary.AppendLine("- Duration per run: ${DurationSeconds}s")
[void]$summary.AppendLine("- Warmup per endpoint: ${WarmupSeconds}s")
[void]$summary.AppendLine("- Runtime profile: $RuntimeProfile")
[void]$summary.AppendLine("- JNI workers: $JniWorkers")
[void]$summary.AppendLine("- JNI queue capacity: $JniQueueCapacity")
[void]$summary.AppendLine("- Dubbo providers: $EffectiveDubboProviders")
[void]$summary.AppendLine("- Use Zookeeper: $UseZookeeper")
[void]$summary.AppendLine("- Native Dubbo connections per endpoint: $NativeConnectionsPerEndpoint")
[void]$summary.AppendLine("- Native Dubbo async workers: $NativeAsyncWorkers")
[void]$summary.AppendLine("- Native Dubbo async queue capacity: $NativeAsyncQueueCapacity")
[void]$summary.AppendLine("- Dubbo max inflight: $DubboMaxInflight")
[void]$summary.AppendLine("- Dubbo catalog adaptive: $DubboCatalogAdaptiveEnabled")
[void]$summary.AppendLine("- Dubbo catalog min inflight: $DubboCatalogMinInflight")
[void]$summary.AppendLine("- Dubbo catalog initial inflight: $DubboCatalogInitialInflight")
[void]$summary.AppendLine("- Dubbo catalog bulkhead: $DubboCatalogMaxInflight")
[void]$summary.AppendLine("- Dubbo catalog response timeout ms: $DubboCatalogResponseTimeoutMs")
[void]$summary.AppendLine("- Dubbo catalog target latency ms: $DubboCatalogTargetLatencyMs")
[void]$summary.AppendLine("- Dubbo catalog high latency ms: $DubboCatalogHighLatencyMs")
[void]$summary.AppendLine("- Dubbo catalog RPC workers: $DubboCatalogRpcWorkers")
[void]$summary.AppendLine("- Dubbo catalog RPC queue capacity: $DubboCatalogRpcQueueCapacity")
[void]$summary.AppendLine("- Randomized order: $($RandomizeRunOrder.IsPresent)")
[void]$summary.AppendLine("- App port: $AppPort")
[void]$summary.AppendLine("- Results: $ResultsDir")
[void]$summary.AppendLine()
[void]$summary.AppendLine("| scenario | endpoint | c | rps | avg ms | p95 ms | p99 ms | errors | WS after MB | WS idle MB | private idle MB |")
[void]$summary.AppendLine("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
foreach ($row in $results) {
    [void]$summary.AppendLine("| $($row.scenario) | $($row.endpoint) | $($row.concurrency) | $($row.rps) | $($row.avg_ms) | $($row.p95_ms) | $($row.p99_ms) | $($row.errors) | $($row.working_set_after_mb) | $($row.working_set_idle_mb) | $($row.private_idle_mb) |")
}

[void]$summary.AppendLine()
[void]$summary.AppendLine("## Baseline vs Dubbo Enabled on Non-Dubbo Endpoints")
[void]$summary.AppendLine()
[void]$summary.AppendLine("| endpoint | c | baseline rps | dubbo-enabled rps | rps delta % | baseline p99 ms | dubbo-enabled p99 ms | p99 delta % | idle WS delta MB |")
[void]$summary.AppendLine("|---|---:|---:|---:|---:|---:|---:|---:|---:|")
foreach ($endpointName in @("raw", "candidates")) {
    foreach ($concurrency in $ConcurrencyList) {
        $base = $results | Where-Object { $_.scenario -eq "baseline_no_dubbo" -and $_.endpoint -eq $endpointName -and $_.concurrency -eq $concurrency } | Select-Object -First 1
        $dubbo = $results | Where-Object { $_.scenario -eq "dubbo_enabled" -and $_.endpoint -eq $endpointName -and $_.concurrency -eq $concurrency } | Select-Object -First 1
        if ($base -and $dubbo) {
            $rpsDelta = if ($base.rps -gt 0) { [Math]::Round((($dubbo.rps - $base.rps) / $base.rps) * 100, 2) } else { 0 }
            $p99Delta = if ($base.p99_ms -gt 0) { [Math]::Round((($dubbo.p99_ms - $base.p99_ms) / $base.p99_ms) * 100, 2) } else { 0 }
            $wsDelta = [Math]::Round($dubbo.working_set_idle_mb - $base.working_set_idle_mb, 2)
            [void]$summary.AppendLine("| $endpointName | $concurrency | $($base.rps) | $($dubbo.rps) | $rpsDelta | $($base.p99_ms) | $($dubbo.p99_ms) | $p99Delta | $wsDelta |")
        }
    }
}

$summaryPath = Join-Path $ResultsDir "summary.md"
$summary.ToString() | Set-Content -Path $summaryPath -Encoding UTF8

Write-Output "summary: $summaryPath"
Write-Output "json: $resultsPath"
