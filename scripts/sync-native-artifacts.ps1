[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$WindowsBinary,

    [Parameter(Mandatory = $true)]
    [string]$LinuxBinary,

    [string]$NativeSourceDirectory = (Join-Path $PSScriptRoot "..\..\rust-spring"),

    [string]$CacheResourcesDirectory = (Join-Path $PSScriptRoot "..\..\java-rust-cache\src\main\resources\native"),

    [int]$RestAbi = 24,

    [int]$DubboAbi = 7,

    [int]$RedisAbi = 6
)

$ErrorActionPreference = "Stop"

function Resolve-RequiredFile([string]$PathValue) {
    $resolved = Resolve-Path -LiteralPath $PathValue -ErrorAction Stop
    if (-not (Test-Path -LiteralPath $resolved.Path -PathType Leaf)) {
        throw "Native artifact is not a file: $PathValue"
    }
    return $resolved.Path
}

function Read-CrateVersion([string]$CargoToml) {
    $match = Select-String -LiteralPath $CargoToml -Pattern '^version\s*=\s*"([^"]+)"' | Select-Object -First 1
    if ($null -eq $match) {
        throw "Cannot read crate version from $CargoToml"
    }
    return $match.Matches[0].Groups[1].Value
}

function Read-SourceRevision([string]$Repository) {
    $revision = (& git -C $Repository rev-parse --short=12 HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($revision)) {
        throw "Cannot read native source revision from $Repository"
    }
    $dirty = & git -C $Repository status --porcelain --untracked-files=no
    if ($LASTEXITCODE -ne 0) {
        throw "Cannot inspect native source worktree state in $Repository"
    }
    if ($dirty) {
        return "${revision}-dirty"
    }
    return $revision
}

$windowsSource = Resolve-RequiredFile $WindowsBinary
$linuxSource = Resolve-RequiredFile $LinuxBinary
$sourceRepository = (Resolve-Path -LiteralPath $NativeSourceDirectory).Path
$resources = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\src\main\resources\native")).Path
$windowsDirectory = Join-Path $resources "windows-x64"
$linuxDirectory = Join-Path $resources "linux-x64"
New-Item -ItemType Directory -Force -Path $windowsDirectory, $linuxDirectory | Out-Null

$windowsTarget = Join-Path $windowsDirectory "rust_hyper.dll"
$linuxTarget = Join-Path $linuxDirectory "librust_hyper.so"
Copy-Item -LiteralPath $windowsSource -Destination $windowsTarget -Force
Copy-Item -LiteralPath $linuxSource -Destination $linuxTarget -Force

$sourceRevision = Read-SourceRevision $sourceRepository
$crateVersion = Read-CrateVersion (Join-Path $sourceRepository "Cargo.toml")
$windowsHash = (Get-FileHash -LiteralPath $windowsTarget -Algorithm SHA256).Hash.ToLowerInvariant()
$linuxHash = (Get-FileHash -LiteralPath $linuxTarget -Algorithm SHA256).Hash.ToLowerInvariant()
$manifest = @"
schema=2
rest.abi=$RestAbi
dubbo.abi=$DubboAbi
redis.abi=$RedisAbi
crate.version=$crateVersion
source.revision=$sourceRevision
windows-x64.sha256=$windowsHash
linux-x64.sha256=$linuxHash
"@
$manifestPath = Join-Path $resources "native-provenance.properties"
[System.IO.File]::WriteAllText($manifestPath, $manifest, [System.Text.UTF8Encoding]::new($false))

$cacheResources = [System.IO.Path]::GetFullPath($CacheResourcesDirectory)
$cacheWindowsDirectory = Join-Path $cacheResources "windows-x64"
$cacheLinuxDirectory = Join-Path $cacheResources "linux-x64"
New-Item -ItemType Directory -Force -Path $cacheResources, $cacheWindowsDirectory, $cacheLinuxDirectory | Out-Null
Copy-Item -LiteralPath $windowsSource -Destination (Join-Path $cacheWindowsDirectory "rust_hyper-windows-x64.dll") -Force
Copy-Item -LiteralPath $linuxSource -Destination (Join-Path $cacheLinuxDirectory "librust_hyper-linux-x64.so") -Force
[System.IO.File]::WriteAllText(
    (Join-Path $cacheResources "native-provenance.properties"),
    $manifest,
    [System.Text.UTF8Encoding]::new($false))

Write-Host "Native artifacts synchronized."
Write-Host "  source revision: $sourceRevision"
Write-Host "  Windows SHA-256: $windowsHash"
Write-Host "  Linux SHA-256:   $linuxHash"
Write-Host "  manifest:        $manifestPath"
Write-Host "  cache resources: $cacheResources"
