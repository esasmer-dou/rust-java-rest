[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$WindowsBinary,

    [Parameter(Mandatory = $true)]
    [string]$LinuxBinary,

    [Parameter(Mandatory = $true)]
    [string]$WindowsMetadata,

    [Parameter(Mandatory = $true)]
    [string]$WindowsChecksum,

    [Parameter(Mandatory = $true)]
    [string]$LinuxMetadata,

    [Parameter(Mandatory = $true)]
    [string]$LinuxChecksum,

    [string]$NativeSourceDirectory = (Join-Path $PSScriptRoot "..\..\rust-spring"),

    [string]$CacheResourcesDirectory = (Join-Path $PSScriptRoot "..\..\java-rust-cache\src\main\resources\native"),

    [int]$RestAbi = 29,

    [int]$DubboAbi = 7,

    [int]$RedisAbi = 6,

    [int]$GlowrootAbi = 3
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

function Read-BuildMetadata([string]$PathValue, [string]$ExpectedArtifactName) {
    $values = ConvertFrom-StringData (Get-Content -Raw -LiteralPath (Resolve-RequiredFile $PathValue))
    if ($values.schema -ne "1" -or $values.'artifact.name' -ne $ExpectedArtifactName) {
        throw "Unexpected native build metadata: $PathValue"
    }
    return $values
}

function Assert-ArtifactChecksum([string]$Binary, [string]$ChecksumFile) {
    $checksumPath = Resolve-RequiredFile $ChecksumFile
    $expected = ((Get-Content -Raw -LiteralPath $checksumPath).Trim() -split '\s+')[0].ToLowerInvariant()
    $actual = (Get-FileHash -LiteralPath $Binary -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($expected -ne $actual) {
        throw "Native CI checksum mismatch for ${Binary}: expected $expected but found $actual"
    }
    return $actual
}

function Read-SourceRevision([string]$Repository) {
    $revision = (& git -C $Repository rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($revision)) {
        throw "Cannot read native source revision from $Repository"
    }
    $dirty = & git -C $Repository status --porcelain --untracked-files=no
    if ($LASTEXITCODE -ne 0) {
        throw "Cannot inspect native source worktree state in $Repository"
    }
    if ($dirty) {
        throw "Native source worktree must be clean before packaging: $Repository"
    }
    return $revision
}

$windowsSource = Resolve-RequiredFile $WindowsBinary
$linuxSource = Resolve-RequiredFile $LinuxBinary
$sourceRepository = (Resolve-Path -LiteralPath $NativeSourceDirectory).Path
$sourceRevisionFull = (& git -C $sourceRepository rev-parse HEAD).Trim()
$windowsMeta = Read-BuildMetadata $WindowsMetadata "windows-x64-rust_hyper"
$linuxMeta = Read-BuildMetadata $LinuxMetadata "linux-x64-librust_hyper"
if ($windowsMeta.'source.revision' -ne $sourceRevisionFull `
        -or $linuxMeta.'source.revision' -ne $sourceRevisionFull) {
    throw "Native artifacts were not built from the checked-out revision $sourceRevisionFull"
}
$verifiedWindowsHash = Assert-ArtifactChecksum $windowsSource $WindowsChecksum
$verifiedLinuxHash = Assert-ArtifactChecksum $linuxSource $LinuxChecksum
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
if ($windowsHash -ne $verifiedWindowsHash -or $linuxHash -ne $verifiedLinuxHash) {
    throw "Native artifact changed while being synchronized"
}
$manifest = @"
schema=2
rest.abi=$RestAbi
dubbo.abi=$DubboAbi
redis.abi=$RedisAbi
glowroot.abi=$GlowrootAbi
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
