Set-StrictMode -Version Latest

function Get-MavenProjectVersion {
    param([Parameter(Mandatory = $true)][string] $PomPath)

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

function Convert-ToIntList {
    param(
        [Parameter(Mandatory = $true)][object] $Value,
        [Parameter(Mandatory = $true)][string] $Name
    )

    $values = @(
        if ($Value -is [array]) {
            $Value | ForEach-Object { [int] $_ }
        } else {
            "$Value" -split "[,\s]+" |
                Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
                ForEach-Object { [int] $_ }
        }
    )
    if ($values.Count -eq 0 -or @($values | Where-Object { $_ -lt 1 }).Count -gt 0) {
        throw "$Name must contain positive integers."
    }
    return $values
}

function New-DubboBenchmarkContext {
    param([Parameter(Mandatory = $true)][string] $ScriptDirectory)

    $frameworkRoot = Resolve-Path (Join-Path $ScriptDirectory "..\..")
    $workspaceRoot = Resolve-Path (Join-Path $frameworkRoot "..")
    return [PSCustomObject]@{
        FrameworkRoot = [string] $frameworkRoot
        WorkspaceRoot = [string] $workspaceRoot
        DubboRoot = Join-Path $workspaceRoot "java-rust-dubbo"
        ConsumerRoot = Join-Path $workspaceRoot "rest-sample-dubbo-consumer"
        ProviderRoot = Join-Path $workspaceRoot "rest-sample-dubbo-provider"
        UtilityRoot = Join-Path $workspaceRoot "rest-sample-utility"
        ModelRoot = Join-Path $workspaceRoot "rust-sample-model"
        LoadRunner = Join-Path $ScriptDirectory "load_runner.js"
        ConsumerMain = "com.reactor.sample.dubbo.consumer.nativestatic.NativeStaticConsumerApplication"
    }
}

function Invoke-MavenProject {
    param(
        [Parameter(Mandatory = $true)][string] $PomPath,
        [Parameter(Mandatory = $true)][string[]] $Goals,
        [string] $Profile = ""
    )

    $arguments = @("-q", "-f", $PomPath)
    if (-not [string]::IsNullOrWhiteSpace($Profile)) {
        $arguments += "-P$Profile"
    }
    $arguments += $Goals
    & mvn @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Maven failed for $PomPath with profile '$Profile'."
    }
}

function Initialize-NativeStaticBenchmarkArtifacts {
    param(
        [Parameter(Mandatory = $true)] $Context,
        [switch] $SkipBuild
    )

    $classpathFile = Join-Path $Context.ConsumerRoot "target\dubbo-benchmark-classpath.txt"
    if (-not $SkipBuild) {
        Invoke-MavenProject -PomPath (Join-Path $Context.ModelRoot "pom.xml") `
            -Goals @("clean", "install", "-DskipTests")
        Invoke-MavenProject -PomPath (Join-Path $Context.UtilityRoot "pom.xml") `
            -Goals @("clean", "install", "-DskipTests")
        Invoke-MavenProject -PomPath (Join-Path $Context.FrameworkRoot "pom.xml") `
            -Goals @("clean", "install", "-DskipTests")
        Invoke-MavenProject -PomPath (Join-Path $Context.DubboRoot "pom.xml") `
            -Goals @("clean", "install", "-DskipTests")
        Invoke-MavenProject -PomPath (Join-Path $Context.ProviderRoot "pom.xml") `
            -Profile "catalog-static-provider" -Goals @("clean", "package", "-DskipTests")
        Invoke-MavenProject -PomPath (Join-Path $Context.ConsumerRoot "pom.xml") `
            -Profile "native-static-consumer" `
            -Goals @("clean", "package", "dependency:build-classpath", "-Dmdep.outputFile=target/dubbo-benchmark-classpath.txt", "-DskipTests")
    }

    $providerVersion = Get-MavenProjectVersion (Join-Path $Context.ProviderRoot "pom.xml")
    $providerJar = Join-Path $Context.ProviderRoot "target\rest-sample-dubbo-provider-$providerVersion.jar"
    $consumerClasses = Join-Path $Context.ConsumerRoot "target\classes"
    foreach ($required in @($providerJar, $consumerClasses, $classpathFile, $Context.LoadRunner)) {
        if (-not (Test-Path $required)) {
            throw "Required benchmark artifact does not exist: $required. Run without -SkipBuild first."
        }
    }

    $dependencyClasspath = (Get-Content -Raw -Path $classpathFile).Trim()
    if ([string]::IsNullOrWhiteSpace($dependencyClasspath)) {
        throw "Consumer runtime classpath is empty: $classpathFile"
    }
    $Context | Add-Member -NotePropertyName ProviderJar -NotePropertyValue $providerJar -Force
    $Context | Add-Member -NotePropertyName ConsumerClasspath `
        -NotePropertyValue "$consumerClasses$([IO.Path]::PathSeparator)$dependencyClasspath" -Force
    return $Context
}

function Wait-TcpPort {
    param(
        [Parameter(Mandatory = $true)][int] $Port,
        [int] $TimeoutSeconds = 30,
        $Process = $null,
        [string] $ErrorLog = ""
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ($null -ne $Process -and $Process.HasExited) {
            $detail = if (Test-Path $ErrorLog) { Get-Content -Raw $ErrorLog } else { "" }
            throw "Process exited before TCP port $Port opened. $detail"
        }
        $client = [Net.Sockets.TcpClient]::new()
        try {
            $connect = $client.ConnectAsync("127.0.0.1", $Port)
            if ($connect.Wait(250) -and $client.Connected) {
                return
            }
        } catch {
            # Retry until the explicit deadline.
        } finally {
            $client.Dispose()
        }
        Start-Sleep -Milliseconds 250
    }
    throw "TCP port $Port did not open within ${TimeoutSeconds}s."
}

function Wait-HttpSuccess {
    param(
        [Parameter(Mandatory = $true)][string] $Url,
        [int] $TimeoutSeconds = 40,
        $Process = $null,
        [string] $ErrorLog = ""
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ($null -ne $Process -and $Process.HasExited) {
            $detail = if (Test-Path $ErrorLog) { Get-Content -Raw $ErrorLog } else { "" }
            throw "Process exited before HTTP readiness succeeded. $detail"
        }
        try {
            Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 2 | Out-Null
            return
        } catch {
            Start-Sleep -Milliseconds 250
        }
    }
    throw "HTTP readiness failed within ${TimeoutSeconds}s: $Url"
}

function Start-CatalogStaticProvider {
    param(
        [Parameter(Mandatory = $true)] $Context,
        [Parameter(Mandatory = $true)][string] $ResultsDirectory,
        [Parameter(Mandatory = $true)][int] $Port
    )

    $stdout = Join-Path $ResultsDirectory "provider.out.log"
    $stderr = Join-Path $ResultsDirectory "provider.err.log"
    $arguments = @(
        "-Ddubbo.provider.port=$Port",
        "-Ddubbo.provider.host=127.0.0.1",
        "-Ddubbo.provider.bind-host=127.0.0.1",
        "-Dreactor.dubbo.registry-enabled=false",
        "-jar",
        $Context.ProviderJar
    )
    $process = Start-Process -FilePath "java" -ArgumentList $arguments `
        -WorkingDirectory $Context.ProviderRoot -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr -WindowStyle Hidden -PassThru
    Wait-TcpPort -Port $Port -Process $process -ErrorLog $stderr
    return $process
}

function Start-NativeStaticConsumer {
    param(
        [Parameter(Mandatory = $true)] $Context,
        [Parameter(Mandatory = $true)][string] $ResultsDirectory,
        [Parameter(Mandatory = $true)][string] $RunName,
        [Parameter(Mandatory = $true)][int] $Port,
        [Parameter(Mandatory = $true)][string] $Providers,
        [Parameter(Mandatory = $true)][string] $RuntimeProfile,
        [Parameter(Mandatory = $true)][int] $JniWorkers,
        [Parameter(Mandatory = $true)][int] $JniQueueCapacity,
        [Parameter(Mandatory = $true)][int] $ConnectionsPerEndpoint,
        [Parameter(Mandatory = $true)][int] $MaxIdleConnectionsPerEndpoint,
        [Parameter(Mandatory = $true)][int] $NativeAsyncWorkers,
        [Parameter(Mandatory = $true)][int] $NativeAsyncQueueCapacity,
        [Parameter(Mandatory = $true)][string] $NativeAsyncTransport,
        [Parameter(Mandatory = $true)][int] $DubboMaxInflight
    )

    $stdout = Join-Path $ResultsDirectory "$RunName.app.out.log"
    $stderr = Join-Path $ResultsDirectory "$RunName.app.err.log"
    $arguments = @(
        "-Xms8m",
        "-Xmx48m",
        "-Xss256k",
        "-Xquickstart",
        "-Xtune:virtualized",
        "-Xshareclasses:none",
        "-Dserver.port=$Port",
        "-Dreactor.runtime.profile=$RuntimeProfile",
        "-Dsample.dubbo.discovery=static",
        "-Dreactor.dubbo.enabled=true",
        "-Dreactor.dubbo.providers=$Providers",
        "-Dreactor.dubbo.max-inflight=$DubboMaxInflight",
        "-Dreactor.dubbo.native-connections-per-endpoint=$ConnectionsPerEndpoint",
        "-Dreactor.dubbo.native-max-idle-connections-per-endpoint=$MaxIdleConnectionsPerEndpoint",
        "-Dreactor.dubbo.native-async-workers=$NativeAsyncWorkers",
        "-Dreactor.dubbo.native-async-queue-capacity=$NativeAsyncQueueCapacity",
        "-Dreactor.dubbo.native-async-transport=$NativeAsyncTransport",
        "-Dreactor.rust.jni.workers=$JniWorkers",
        "-Dreactor.rust.jni.queue-capacity=$JniQueueCapacity",
        "-Dreactor.rust.log.level=error",
        "-Dreactor.rust.java.log.level=warn",
        "-Dfile.encoding=UTF-8",
        "-cp",
        $Context.ConsumerClasspath,
        $Context.ConsumerMain
    )
    $process = Start-Process -FilePath "java" -ArgumentList $arguments `
        -WorkingDirectory $Context.ConsumerRoot -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr -WindowStyle Hidden -PassThru
    Wait-HttpSuccess -Url "http://127.0.0.1:$Port/app/ready" -Process $process -ErrorLog $stderr
    return $process
}

function Stop-ProcessSafely {
    param($Process)

    if ($null -ne $Process -and -not $Process.HasExited) {
        Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
        $Process.WaitForExit(5000) | Out-Null
    }
}

function Get-ProcessMemorySnapshot {
    param([Parameter(Mandatory = $true)][int] $ProcessId)

    $process = Get-Process -Id $ProcessId -ErrorAction Stop
    return [PSCustomObject]@{
        working_set_mb = [Math]::Round($process.WorkingSet64 / 1MB, 2)
        private_mb = [Math]::Round($process.PrivateMemorySize64 / 1MB, 2)
        threads = $process.Threads.Count
        cpu_sec = [Math]::Round($process.TotalProcessorTime.TotalSeconds, 3)
    }
}

function Invoke-JsonEndpoint {
    param([Parameter(Mandatory = $true)][string] $Url)

    return (Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 5).Content | ConvertFrom-Json
}

function Reset-ConsumerMetrics {
    param([Parameter(Mandatory = $true)][int] $Port)

    Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port/app/metrics/reset" -TimeoutSec 5 | Out-Null
}

function Invoke-DubboLoad {
    param(
        [Parameter(Mandatory = $true)] $Context,
        [Parameter(Mandatory = $true)][string] $ResultsDirectory,
        [Parameter(Mandatory = $true)][string] $Name,
        [Parameter(Mandatory = $true)][string] $Url,
        [Parameter(Mandatory = $true)][int] $Concurrency,
        [Parameter(Mandatory = $true)][int] $DurationSeconds
    )

    $raw = & node $Context.LoadRunner --url $Url --concurrency $Concurrency `
        --duration-sec $DurationSeconds --timeout-ms 10000
    if ($LASTEXITCODE -ne 0) {
        throw "Load runner failed for $Url at concurrency $Concurrency."
    }
    $raw | Set-Content -Path (Join-Path $ResultsDirectory "$Name.load.json") -Encoding UTF8
    return $raw | ConvertFrom-Json
}
