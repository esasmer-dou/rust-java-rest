param(
    [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
    [string]$JavaExe = "java",
    [string]$MainClass = "com.reactor.rust.example.ReactorRustHyperApplication",
    [string]$Profile = "fast-start",
    [ValidateSet("none", "openj9-scc-aot", "openj9-scc-aot-quickstart", "openj9-micro-rss", "openj9-idle-rss")]
    [string]$JvmPreset = "none",
    [int]$Port = 18080,
    [int]$TimeoutSeconds = 20,
    [switch]$Build,
    [string[]]$JavaOptsAppend = @(),
    [string]$OutputDir = (Join-Path $PSScriptRoot "results\\startup")
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-MavenProjectVersion([string]$PomPath) {
    [xml]$pom = Get-Content -LiteralPath $PomPath
    return $pom.project.version
}

function Resolve-JvmOptions {
    param(
        [string]$Preset,
        [string]$CacheRoot,
        [string]$CacheName
    )
    $opts = New-Object System.Collections.Generic.List[string]
    $opts.Add("-Dfile.encoding=UTF-8")
    $opts.Add("-Dserver.port=$Port")
    $opts.Add("-Dreactor.runtime.profile=$Profile")
    $opts.Add("-Dreactor.startup.component-index.required=false")
    $opts.Add("-Dreactor.native.extract.cache.enabled=true")

    if ($Preset -eq "openj9-scc-aot" -or $Preset -eq "openj9-scc-aot-quickstart") {
        New-Item -ItemType Directory -Force -Path $CacheRoot | Out-Null
        $opts.Add("-Xshareclasses:name=$CacheName,cacheDir=$CacheRoot,nonfatal")
        $opts.Add("-Xscmx64m")
        $opts.Add("-Xscmaxaot32m")
        $opts.Add("-Xtune:virtualized")
    }
    if ($Preset -eq "openj9-scc-aot-quickstart") {
        $opts.Add("-Xquickstart")
    }
    if ($Preset -eq "openj9-micro-rss" -or $Preset -eq "openj9-idle-rss") {
        $opts.Add("-Xms8m")
        $opts.Add("-Xmx48m")
        $opts.Add("-Xss256k")
        $opts.Add("-Xquickstart")
        $opts.Add("-Xtune:virtualized")
        $opts.Add("-Xshareclasses:none")
        $opts.Add("-XX:ActiveProcessorCount=1")
    }
    if ($Preset -eq "openj9-idle-rss") {
        $opts.Add("-Xnojit")
    }
    return $opts
}

function Quote-ProcessArgument {
    param([string]$Value)
    if ($null -eq $Value) {
        return '""'
    }
    if ($Value -notmatch '[\s"]') {
        return $Value
    }
    return '"' + ($Value -replace '"', '\"') + '"'
}

function Stop-ProcessSafe {
    param([System.Diagnostics.Process]$Process)
    if ($null -eq $Process -or $Process.HasExited) {
        return
    }
    try {
        $Process.Kill($true)
    } catch {
        try {
            $Process.Kill()
        } catch {
            Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
        }
    }
}

$ProjectRoot = (Resolve-Path $ProjectRoot).Path
$pom = Join-Path $ProjectRoot "pom.xml"
$version = Get-MavenProjectVersion $pom

if ($Build) {
    Push-Location $ProjectRoot
    try {
        mvn -q -DskipTests package
    } finally {
        Pop-Location
    }
}

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$runDir = Join-Path $OutputDir "startup_${timestamp}_${Profile}_${JvmPreset}"
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

$classpath = @(
    (Join-Path $ProjectRoot "target\\classes"),
    (Join-Path $ProjectRoot "target\\dependency\\*")
) -join [IO.Path]::PathSeparator

$cacheRoot = Join-Path $ProjectRoot "target\\openj9-scc"
$cacheName = "rust-java-rest-$version-$Profile"
$jvmOptions = Resolve-JvmOptions -Preset $JvmPreset -CacheRoot $cacheRoot -CacheName $cacheName

$arguments = New-Object System.Collections.Generic.List[string]
foreach ($opt in $jvmOptions) {
    $arguments.Add($opt)
}
foreach ($opt in $JavaOptsAppend) {
    if (-not [string]::IsNullOrWhiteSpace($opt)) {
        $arguments.Add($opt)
    }
}
$arguments.Add("-cp")
$arguments.Add($classpath)
$arguments.Add($MainClass)

$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName = $JavaExe
$psi.Arguments = (($arguments | ForEach-Object { Quote-ProcessArgument $_ }) -join " ")
$psi.WorkingDirectory = $ProjectRoot
$psi.UseShellExecute = $false
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.CreateNoWindow = $true

$proc = [System.Diagnostics.Process]::new()
$proc.StartInfo = $psi
$sw = [System.Diagnostics.Stopwatch]::StartNew()
[void]$proc.Start()

$startup = $null
$errorText = $null
try {
    while ($sw.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        if ($proc.HasExited) {
            $errorText = "Process exited before readiness with code $($proc.ExitCode)"
            break
        }
        try {
            $startup = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/diagnostics/startup" -TimeoutSec 1
            if ($null -ne $startup -and $startup.ready_ms -ge 0) {
                break
            }
        } catch {
            Start-Sleep -Milliseconds 100
        }
    }
} finally {
    if (-not $proc.HasExited) {
        Stop-ProcessSafe -Process $proc
    }
    $proc.WaitForExit(5000) | Out-Null
    $stdout = $proc.StandardOutput.ReadToEnd()
    $stderr = $proc.StandardError.ReadToEnd()
    $stdout | Set-Content -LiteralPath (Join-Path $runDir "stdout.log") -Encoding UTF8
    $stderr | Set-Content -LiteralPath (Join-Path $runDir "stderr.log") -Encoding UTF8
}

$result = [ordered]@{
    timestamp = $timestamp
    version = $version
    profile = $Profile
    jvmPreset = $JvmPreset
    javaExe = $JavaExe
    port = $Port
    elapsedMs = [int64]$sw.Elapsed.TotalMilliseconds
    readyMs = if ($startup) { [int64]$startup.ready_ms } else { -1 }
    startup = $startup
    error = $errorText
    command = @($JavaExe) + $arguments
}

$json = $result | ConvertTo-Json -Depth 8
$json | Set-Content -LiteralPath (Join-Path $runDir "startup.json") -Encoding UTF8
$json
