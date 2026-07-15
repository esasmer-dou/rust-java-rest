param(
    [int] $HostPortBase = 18120,
    [string] $ResultsDir = "",
    [string] $ContainerMemory = "96m",
    [ValidateSet("current", "cpu1", "cpu1-xss192", "cpu1-xss160", "cpu1-xss128", "cpu1-nojit", "cpu1-nojit-xss160", "cpu1-nojit-xss128")]
    [string] $JvmPreset = "cpu1",
    [ValidateSet("core-runtime", "classes")]
    [string] $FrameworkArtifactMode = "core-runtime",
    [ValidateSet("classes", "full-jar", "native-static")]
    [string] $DubboArtifactMode = "classes",
    [int] $IdleSeconds = 0,
    [switch] $OnlyDubbo,
    [switch] $SkipBuild,
    [switch] $SkipZookeeper,
    [switch] $KeepContainers
)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$FrameworkRoot = Resolve-Path (Join-Path $ScriptDir "..")
$WorkspaceRoot = Resolve-Path (Join-Path $FrameworkRoot "..")
$DubboRoot = Join-Path $WorkspaceRoot "java-rust-dubbo"
$ConsumerRoot = Join-Path $WorkspaceRoot "rest-sample-dubbo-consumer"

if ([string]::IsNullOrWhiteSpace($ResultsDir)) {
    $ResultsDir = Join-Path $ScriptDir ("results\micro_runtime_rss_{0}" -f (Get-Date -Format "yyyyMMdd_HHmmss"))
}
$ResultsDir = [System.IO.Path]::GetFullPath($ResultsDir)
New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null

$NetworkName = "reactor-rss-matrix-net"
$FrameworkImage = "rust-java-rest:rss-matrix"
$ConsumerImage = "rest-sample-dubbo-consumer:rss-matrix-$DubboArtifactMode"
$ZookeeperContainer = "reactor-rss-zookeeper"

function Invoke-Checked {
    param(
        [string] $FilePath,
        [string[]] $Arguments,
        [string] $WorkingDirectory
    )
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        Push-Location $WorkingDirectory
        $output = & $FilePath @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        Pop-Location
        $ErrorActionPreference = $previousErrorAction
    }
    $output | ForEach-Object { Write-Host $_ }
    if ($exitCode -ne 0) {
        throw "Command failed: $FilePath $($Arguments -join ' ')`n$($output -join "`n")"
    }
}

function Join-JavaOptions {
    param([string[]] $Parts)
    return (($Parts | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join " ")
}

function Resolve-CommonJavaOptions {
    $parts = New-Object System.Collections.Generic.List[string]
    $parts.Add("-Xms8m")
    $parts.Add("-Xmx48m")
    if ($JvmPreset -like "*xss128") {
        $parts.Add("-Xss128k")
    } elseif ($JvmPreset -like "*xss160") {
        $parts.Add("-Xss160k")
    } elseif ($JvmPreset -like "*xss192") {
        $parts.Add("-Xss192k")
    } else {
        $parts.Add("-Xss256k")
    }
    $parts.Add("-Xquickstart")
    $parts.Add("-Xtune:virtualized")
    $parts.Add("-Xshareclasses:none")
    $parts.Add("-Dfile.encoding=UTF-8")
    $parts.Add("-Djava.security.egd=file:/dev/./urandom")
    $parts.Add("-Dreactor.rust.log.level=error")
    $parts.Add("-Dreactor.rust.java.log.level=warn")

    if ($JvmPreset -like "cpu1*") {
        $parts.Add("-XX:ActiveProcessorCount=1")
    }
    if ($JvmPreset -like "*nojit*") {
        $parts.Add("-Xnojit")
    }
    return [string[]]$parts
}

function Find-FrameworkSampleJar {
    $jar = Get-ChildItem -Path (Join-Path $FrameworkRoot "sample\target") -Filter "rust-java-rest-*-sample.jar" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $jar) {
        throw "Framework sample jar not found. Build core, then run mvn package in sample/."
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
    return $jar.FullName
}

function Ensure-RuntimeDependencyDir {
    $dependencyDir = Join-Path $FrameworkRoot "target\dependency"
    if ((Test-Path $dependencyDir) -and
            (Get-ChildItem -Path $dependencyDir -Filter "*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1)) {
        return
    }
    Invoke-Checked -FilePath "mvn" -Arguments @(
        "-q", "-DskipTests", "dependency:copy-dependencies",
        "-DincludeScope=runtime",
        "-DoutputDirectory=target/dependency"
    ) -WorkingDirectory $FrameworkRoot
}

function Consumer-ProfileArgs {
    if ($DubboArtifactMode -eq "native-static") {
        return @("-Pnative-static-consumer")
    }
    return @("-Pfull-dubbo-consumer")
}

function Consumer-DependencyDir {
    return (Join-Path $ConsumerRoot "target\dependency-$DubboArtifactMode")
}

function Prepare-Builds {
    if (-not $SkipZookeeper -and $DubboArtifactMode -ne "classes") {
        throw "ZooKeeper RSS scenario currently uses legacy classes mode. Use -SkipZookeeper for full-jar/native-static A/B runs."
    }
    if ($SkipBuild) {
        Ensure-RuntimeDependencyDir
        return
    }
    Invoke-Checked -FilePath "mvn" -Arguments @("-q", "-DskipTests", "install") -WorkingDirectory $FrameworkRoot
    Invoke-Checked -FilePath "mvn" -Arguments @("-q", "-DskipTests", "package") -WorkingDirectory (Join-Path $FrameworkRoot "sample")
    Invoke-Checked -FilePath "mvn" -Arguments @("-q", "-DskipTests", "install") -WorkingDirectory $DubboRoot
    Invoke-Checked -FilePath "mvn" -Arguments (@(Consumer-ProfileArgs) + @("-q", "-DskipTests", "package")) -WorkingDirectory $ConsumerRoot
    Ensure-RuntimeDependencyDir

    if ($DubboArtifactMode -ne "classes") {
        $consumerDepDir = Consumer-DependencyDir
        New-Item -ItemType Directory -Force -Path $consumerDepDir | Out-Null
        Invoke-Checked -FilePath "mvn" -Arguments (@(Consumer-ProfileArgs) + @(
            "-q",
            "-DskipTests",
            "dependency:copy-dependencies",
            "-DincludeScope=runtime",
            "-DexcludeArtifactIds=rust-java-rest",
            "-DoutputDirectory=target/dependency-$DubboArtifactMode"
        )) -WorkingDirectory $ConsumerRoot
        return
    }

    $zkDepDir = Join-Path $ConsumerRoot "target\dependency-zk"
    New-Item -ItemType Directory -Force -Path $zkDepDir | Out-Null
    Invoke-Checked -FilePath "mvn" -Arguments @(
        "-q",
        "-Pfull-dubbo-consumer,zookeeper-discovery",
        "-DskipTests",
        "dependency:copy-dependencies",
        "-DincludeScope=runtime",
        "-DoutputDirectory=target/dependency-zk"
    ) -WorkingDirectory $ConsumerRoot
}

function Build-FrameworkImage {
    $sampleJar = Find-FrameworkSampleJar
    Invoke-Checked -FilePath "docker" -Arguments @(
        "build",
        "-t", $FrameworkImage,
        "-f", (Join-Path $FrameworkRoot "benchmark\docker\framework.Dockerfile"),
        "--build-arg", "JAR_FILE=$sampleJar",
        "."
    ) -WorkingDirectory $FrameworkRoot
}

function Copy-Directory {
    param([string] $Source, [string] $Target)
    if (-not (Test-Path $Source)) {
        throw "Missing directory: $Source"
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Target) | Out-Null
    if (Test-Path $Target) {
        Remove-Item -Recurse -Force -Path $Target
    }
    Copy-Item -Recurse -Path $Source -Destination $Target
}

function Build-ConsumerImage {
    $context = Join-Path $ResultsDir "consumer-image-context"
    if (Test-Path $context) {
        Remove-Item -Recurse -Force -Path $context
    }
    New-Item -ItemType Directory -Force -Path $context | Out-Null

    Copy-Directory -Source (Join-Path $ConsumerRoot "target\classes") -Target (Join-Path $context "consumer\classes")
    if ($FrameworkArtifactMode -eq "core-runtime") {
        $frameworkLib = Join-Path $context "framework\lib"
        New-Item -ItemType Directory -Force -Path $frameworkLib | Out-Null
        Copy-Item -LiteralPath (Find-FrameworkCoreRuntimeJar) -Destination $frameworkLib
    } else {
        Copy-Directory -Source (Join-Path $FrameworkRoot "target\classes") -Target (Join-Path $context "framework\classes")
        $frameworkStartupIndex = Join-Path $context "framework\classes\META-INF\reactor"
        if (Test-Path $frameworkStartupIndex) {
            Remove-Item -Recurse -Force -Path $frameworkStartupIndex
        }
        Copy-Directory -Source (Join-Path $FrameworkRoot "target\dependency") -Target (Join-Path $context "framework\lib")
    }

    if ($DubboArtifactMode -eq "classes") {
        Copy-Directory -Source (Join-Path $DubboRoot "target\classes") -Target (Join-Path $context "dubbo\classes")
        $zkDepDir = Join-Path $ConsumerRoot "target\dependency-zk"
        New-Item -ItemType Directory -Force -Path $zkDepDir | Out-Null
        Copy-Directory -Source $zkDepDir -Target (Join-Path $context "consumer\lib-zk")
    } else {
        Copy-Directory -Source (Consumer-DependencyDir) -Target (Join-Path $context "consumer\lib")
    }

    if ($DubboArtifactMode -eq "classes" -and $FrameworkArtifactMode -eq "classes") {
    @'
FROM ibm-semeru-runtimes:open-21-jre-jammy

WORKDIR /app

ENV MALLOC_ARENA_MAX=2 \
    MALLOC_TRIM_THRESHOLD_=131072 \
    JAVA_OPTS="-Xms8m -Xmx48m -Xss256k -Xquickstart -Xtune:virtualized -Xshareclasses:none -XX:ActiveProcessorCount=1 -Dfile.encoding=UTF-8 -Djava.security.egd=file:/dev/./urandom"

COPY consumer/classes /app/consumer/classes
COPY framework/classes /app/framework/classes
COPY framework/lib /app/framework/lib
COPY dubbo/classes /app/dubbo/classes
COPY consumer/lib-zk /app/consumer/lib-zk

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -cp '/app/consumer/classes:/app/framework/classes:/app/framework/lib/*:/app/dubbo/classes:/app/consumer/lib-zk/*' com.reactor.sample.dubbo.consumer.app.RestSampleDubboConsumerApplication"]
'@ | Set-Content -Path (Join-Path $context "Dockerfile") -Encoding ASCII
    } elseif ($DubboArtifactMode -eq "classes") {
    @'
FROM ibm-semeru-runtimes:open-21-jre-jammy

WORKDIR /app

ENV MALLOC_ARENA_MAX=2 \
    MALLOC_TRIM_THRESHOLD_=131072 \
    JAVA_OPTS="-Xms8m -Xmx48m -Xss256k -Xquickstart -Xtune:virtualized -Xshareclasses:none -XX:ActiveProcessorCount=1 -Dfile.encoding=UTF-8 -Djava.security.egd=file:/dev/./urandom"

COPY consumer/classes /app/consumer/classes
COPY framework/lib /app/framework/lib
COPY dubbo/classes /app/dubbo/classes
COPY consumer/lib-zk /app/consumer/lib-zk

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -cp '/app/consumer/classes:/app/framework/lib/*:/app/dubbo/classes:/app/consumer/lib-zk/*' com.reactor.sample.dubbo.consumer.app.RestSampleDubboConsumerApplication"]
'@ | Set-Content -Path (Join-Path $context "Dockerfile") -Encoding ASCII
    } elseif ($FrameworkArtifactMode -eq "classes") {
    @'
FROM ibm-semeru-runtimes:open-21-jre-jammy

WORKDIR /app

ENV MALLOC_ARENA_MAX=2 \
    MALLOC_TRIM_THRESHOLD_=131072 \
    JAVA_OPTS="-Xms8m -Xmx48m -Xss256k -Xquickstart -Xtune:virtualized -Xshareclasses:none -XX:ActiveProcessorCount=1 -Dfile.encoding=UTF-8 -Djava.security.egd=file:/dev/./urandom"

COPY consumer/classes /app/consumer/classes
COPY framework/classes /app/framework/classes
COPY framework/lib /app/framework/lib
COPY consumer/lib /app/consumer/lib

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -cp '/app/consumer/classes:/app/framework/classes:/app/framework/lib/*:/app/consumer/lib/*' com.reactor.sample.dubbo.consumer.app.RestSampleDubboConsumerApplication"]
'@ | Set-Content -Path (Join-Path $context "Dockerfile") -Encoding ASCII
    } else {
    @'
FROM ibm-semeru-runtimes:open-21-jre-jammy

WORKDIR /app

ENV MALLOC_ARENA_MAX=2 \
    MALLOC_TRIM_THRESHOLD_=131072 \
    JAVA_OPTS="-Xms8m -Xmx48m -Xss256k -Xquickstart -Xtune:virtualized -Xshareclasses:none -XX:ActiveProcessorCount=1 -Dfile.encoding=UTF-8 -Djava.security.egd=file:/dev/./urandom"

COPY consumer/classes /app/consumer/classes
COPY framework/lib /app/framework/lib
COPY consumer/lib /app/consumer/lib

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -cp '/app/consumer/classes:/app/framework/lib/*:/app/consumer/lib/*' com.reactor.sample.dubbo.consumer.app.RestSampleDubboConsumerApplication"]
'@ | Set-Content -Path (Join-Path $context "Dockerfile") -Encoding ASCII
    }

    Invoke-Checked -FilePath "docker" -Arguments @(
        "build",
        "-t", $ConsumerImage,
        "."
    ) -WorkingDirectory $context
}

function Ensure-Network {
    $existing = @(& docker network ls --format "{{.Name}}" | Where-Object { $_ -eq $NetworkName })
    if ($existing.Count -gt 0) {
        return
    }
    Invoke-Checked -FilePath "docker" -Arguments @("network", "create", $NetworkName) -WorkingDirectory $WorkspaceRoot
}

function Remove-Container {
    param([string] $Name)
    $existing = @(& docker ps -a --format "{{.Names}}" | Where-Object { $_ -eq $Name })
    if ($existing.Count -gt 0) {
        & docker rm -f $Name | Out-Null
    }
}

function Save-ContainerLogs {
    param([string] $Container, [string] $Path)
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $logs = & docker logs $Container 2>&1
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    $logs | Set-Content -Path $Path -Encoding UTF8
}

function Save-ThreadSnapshot {
    param([string] $Container, [string] $Path)
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $pidText = (& docker exec $Container pidof java 2>&1) -join " "
        $javaPid = ($pidText -split "\s+" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1)
        if ([string]::IsNullOrWhiteSpace($javaPid)) {
            @("java pid not found") | Set-Content -Path $Path -Encoding UTF8
            return
        }
        $taskIds = & docker exec $Container ls "/proc/$javaPid/task" 2>&1
        $threads = foreach ($taskId in $taskIds) {
            $tid = "$taskId".Trim()
            if ([string]::IsNullOrWhiteSpace($tid)) {
                continue
            }
            $name = ((& docker exec $Container cat "/proc/$javaPid/task/$tid/comm" 2>&1) -join " ").Trim()
            "$tid $name"
        }
        $threads = $threads | Sort-Object { ($_ -split " ", 2)[1] }, { ($_ -split " ", 2)[0] }
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    $threads | Set-Content -Path $Path -Encoding UTF8
}

function Start-Zookeeper {
    if ($SkipZookeeper) {
        return $false
    }
    Remove-Container $ZookeeperContainer
    Invoke-Checked -FilePath "docker" -Arguments @(
        "run", "-d",
        "--name", $ZookeeperContainer,
        "--network", $NetworkName,
        "-p", "2181:2181",
        "zookeeper:3.7.2"
    ) -WorkingDirectory $WorkspaceRoot

    $deadline = (Get-Date).AddSeconds(45)
    while ((Get-Date) -lt $deadline) {
        $running = @(& docker ps --format "{{.Names}}" | Where-Object { $_ -eq $ZookeeperContainer })
        if ($running.Count -gt 0) {
            Start-Sleep -Seconds 5
            return $true
        }
        Start-Sleep -Seconds 1
    }
    return $true
}

function Wait-Http200 {
    param([int] $Port, [string] $Path, [int] $TimeoutSeconds = 45)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port$Path" -TimeoutSec 2
            if ($response.StatusCode -eq 200) {
                return
            }
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    throw "HTTP readiness failed: http://127.0.0.1:$Port$Path"
}

function Invoke-Endpoint {
    param([int] $Port, [string] $Path, [int] $Count = 1)
    for ($i = 0; $i -lt $Count; $i++) {
        try {
            Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port$Path" -TimeoutSec 8 | Out-Null
        } catch {
            # Some phases intentionally call unavailable Dubbo providers to measure first-RPC retained memory.
        }
    }
}

function Convert-KbToMiB {
    param([string] $Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $null
    }
    return [math]::Round(([double] $Value) / 1024.0, 2)
}

function Get-ContainerMemory {
    param([string] $Container)
    $script = 'pid=$(pidof java); pid=${pid%% *}; cat /proc/$pid/smaps_rollup 2>/dev/null; echo __STATUS__; cat /proc/$pid/status'
    $text = (& docker exec $Container sh -c $script 2>$null) -join "`n"
    if ([string]::IsNullOrWhiteSpace($text)) {
        return [PSCustomObject]@{ rss_mib = $null; pss_mib = $null; private_dirty_mib = $null; vmrss_mib = $null; threads = $null }
    }
    $rss = [regex]::Match($text, "(?m)^Rss:\s+(\d+)\s+kB")
    $pss = [regex]::Match($text, "(?m)^Pss:\s+(\d+)\s+kB")
    $privateDirty = [regex]::Match($text, "(?m)^Private_Dirty:\s+(\d+)\s+kB")
    $vmrss = [regex]::Match($text, "(?m)^VmRSS:\s+(\d+)\s+kB")
    $threads = [regex]::Match($text, "(?m)^Threads:\s+(\d+)")
    return [PSCustomObject]@{
        rss_mib = Convert-KbToMiB $rss.Groups[1].Value
        pss_mib = Convert-KbToMiB $pss.Groups[1].Value
        private_dirty_mib = Convert-KbToMiB $privateDirty.Groups[1].Value
        vmrss_mib = Convert-KbToMiB $vmrss.Groups[1].Value
        threads = if ($threads.Success) { [int] $threads.Groups[1].Value } else { $null }
    }
}

function Get-DockerStatsMemory {
    param([string] $Container)
    $raw = (& docker stats --no-stream --format "{{.MemUsage}}" $Container 2>$null) -join ""
    if ([string]::IsNullOrWhiteSpace($raw)) {
        return $null
    }
    $first = ($raw -split "/")[0].Trim()
    $match = [regex]::Match($first, "([0-9.]+)\s*([A-Za-z]+)")
    if (-not $match.Success) {
        return $null
    }
    $value = [double] $match.Groups[1].Value
    $unit = $match.Groups[2].Value.ToLowerInvariant()
    switch ($unit) {
        "kib" { return [math]::Round($value / 1024.0, 2) }
        "mib" { return [math]::Round($value, 2) }
        "gib" { return [math]::Round($value * 1024.0, 2) }
        "kb" { return [math]::Round($value / 1024.0, 2) }
        "mb" { return [math]::Round($value, 2) }
        "gb" { return [math]::Round($value * 1024.0, 2) }
        default { return $null }
    }
}

$Rows = New-Object System.Collections.Generic.List[object]

function Add-Measurement {
    param([string] $Scenario, [string] $Phase, [string] $Container)
    Start-Sleep -Seconds 2
    $mem = Get-ContainerMemory -Container $Container
    $dockerMem = Get-DockerStatsMemory -Container $Container
    $threadPath = Join-Path $ResultsDir ("{0}.{1}.threads.txt" -f $Scenario, $Phase)
    Save-ThreadSnapshot -Container $Container -Path $threadPath
    $Rows.Add([PSCustomObject]@{
        scenario = $Scenario
        phase = $Phase
        smaps_rss_mib = $mem.rss_mib
        smaps_pss_mib = $mem.pss_mib
        private_dirty_mib = $mem.private_dirty_mib
        status_vmrss_mib = $mem.vmrss_mib
        docker_mem_mib = $dockerMem
        threads = $mem.threads
        thread_snapshot = $threadPath
    })
}

function Start-AppContainer {
    param(
        [string] $Name,
        [string] $Image,
        [int] $Port,
        [string] $JavaOpts
    )
    Remove-Container $Name
    $containerId = & docker run -d `
        --name $Name `
        --network $NetworkName `
        -p "${Port}:8080" `
        --memory $ContainerMemory `
        -e "JAVA_OPTS=$JavaOpts" `
        $Image
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($containerId)) {
        throw "Failed to start container: $Name"
    }
}

function Run-Scenario {
    param(
        [string] $Scenario,
        [string] $Image,
        [int] $Port,
        [string] $ReadyPath,
        [string] $JavaOpts,
        [object[]] $Phases
    )
    $container = "rss-$Scenario"
    try {
        Start-AppContainer -Name $container -Image $Image -Port $Port -JavaOpts $JavaOpts
        Wait-Http200 -Port $Port -Path $ReadyPath
        Add-Measurement -Scenario $Scenario -Phase "ready" -Container $container

        foreach ($phase in $Phases) {
            if ($phase.PSObject.Properties.Name -contains "idleSeconds") {
                Start-Sleep -Seconds $phase.idleSeconds
            } else {
                Invoke-Endpoint -Port $Port -Path $phase.path -Count $phase.count
            }
            Add-Measurement -Scenario $Scenario -Phase $phase.name -Container $container
        }

        Save-ContainerLogs -Container $container -Path (Join-Path $ResultsDir "$Scenario.log")
    } finally {
        $existing = @(& docker ps -a --format "{{.Names}}" | Where-Object { $_ -eq $container })
        if ($existing.Count -gt 0) {
            Save-ContainerLogs -Container $container -Path (Join-Path $ResultsDir "$Scenario.final.log")
        }
        if (-not $KeepContainers) {
            Remove-Container $container
        }
    }
}

$CommonJavaOpts = Resolve-CommonJavaOptions

Prepare-Builds
Ensure-Network
Build-FrameworkImage
Build-ConsumerImage
$zookeeperStarted = Start-Zookeeper

try {
    if (-not $OnlyDubbo) {
        Run-Scenario `
            -Scenario "micro-rest" `
            -Image $FrameworkImage `
            -Port $HostPortBase `
            -ReadyPath "/metrics" `
            -JavaOpts (Join-JavaOptions ($CommonJavaOpts + @("-Dreactor.runtime.profile=micro-rest"))) `
            -Phases @(
                [PSCustomObject]@{ name = "small-json-warm"; path = "/api/v1/candidates/direct"; count = 20 },
                [PSCustomObject]@{ name = "native-cache-warm"; path = "/api/v1/heavy/cache?items=100"; count = 50 },
                [PSCustomObject]@{ name = "raw-json-warm"; path = "/api/v1/heavy/raw"; count = 50 }
            )
    }

    $dubboStaticPhases = @(
        [PSCustomObject]@{ name = "metrics"; path = "/api/v1/catalog/dubbo-metrics"; count = 10 },
        [PSCustomObject]@{ name = "first-rpc-no-provider"; path = "/api/v1/catalog/nested"; count = 1 }
    )
    if ($IdleSeconds -gt 0) {
        $dubboStaticPhases += [PSCustomObject]@{ name = "idle-after-warm"; idleSeconds = $IdleSeconds }
    }
    Run-Scenario `
        -Scenario "micro-dubbo-static" `
        -Image $ConsumerImage `
        -Port ($HostPortBase + 1) `
        -ReadyPath "/app/health" `
        -JavaOpts (Join-JavaOptions ($CommonJavaOpts + @(
            "-Dreactor.runtime.profile=micro-dubbo",
            "-Dsample.dubbo.discovery=static",
            "-Dreactor.dubbo.enabled=true",
            "-Dreactor.dubbo.lazy=true",
            "-Dreactor.dubbo.providers=127.0.0.1:20880"
        ))) `
        -Phases $dubboStaticPhases

    if ($zookeeperStarted -and -not $SkipZookeeper) {
        $dubboZkPhases = @(
            [PSCustomObject]@{ name = "metrics"; path = "/api/v1/catalog/dubbo-metrics"; count = 10 },
            [PSCustomObject]@{ name = "first-rpc-no-provider"; path = "/api/v1/catalog/nested"; count = 1 }
        )
        if ($IdleSeconds -gt 0) {
            $dubboZkPhases += [PSCustomObject]@{ name = "idle-after-warm"; idleSeconds = $IdleSeconds }
        }
        Run-Scenario `
            -Scenario "micro-dubbo-zk" `
            -Image $ConsumerImage `
            -Port ($HostPortBase + 2) `
            -ReadyPath "/app/health" `
            -JavaOpts (Join-JavaOptions ($CommonJavaOpts + @(
                "-Dreactor.runtime.profile=micro-dubbo",
                "-Dsample.dubbo.discovery=zookeeper",
                "-Dreactor.dubbo.enabled=true",
                "-Dreactor.dubbo.lazy=true",
                "-Dreactor.dubbo.registry-address=zookeeper://$ZookeeperContainer`:2181"
            ))) `
            -Phases $dubboZkPhases
    }
} finally {
    if (-not $KeepContainers) {
        if (-not $SkipZookeeper) {
            Remove-Container $ZookeeperContainer
        }
    }
}

$csv = Join-Path $ResultsDir "micro_runtime_rss_matrix.csv"
$Rows | Export-Csv -NoTypeInformation -Path $csv -Encoding UTF8

$summary = Join-Path $ResultsDir "summary.md"
$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("# Micro Runtime RSS Matrix")
$lines.Add("")
$lines.Add("- Date: $(Get-Date -Format o)")
$lines.Add("- JVM image: ibm-semeru-runtimes:open-21-jre-jammy")
$lines.Add("- JVM preset: $JvmPreset")
$lines.Add("- JVM options: $(Join-JavaOptions $CommonJavaOpts)")
$lines.Add("- Container memory limit: $ContainerMemory")
$lines.Add("- Framework artifact mode: $FrameworkArtifactMode")
$lines.Add("- Dubbo artifact mode: $DubboArtifactMode")
$lines.Add("- Only Dubbo: $OnlyDubbo")
$lines.Add("- Idle seconds: $IdleSeconds")
$lines.Add("- Source CSV: $csv")
$lines.Add("")
$lines.Add("| Scenario | Phase | smaps RSS MiB | PSS MiB | Private Dirty MiB | Docker Mem MiB | Threads |")
$lines.Add("|---|---|---:|---:|---:|---:|---:|")
foreach ($row in $Rows) {
    $lines.Add("| $($row.scenario) | $($row.phase) | $($row.smaps_rss_mib) | $($row.smaps_pss_mib) | $($row.private_dirty_mib) | $($row.docker_mem_mib) | $($row.threads) |")
}
$lines | Set-Content -Path $summary -Encoding UTF8

Write-Host "RSS matrix complete: $summary"
