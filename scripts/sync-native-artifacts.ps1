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

    [string]$CacheResourcesDirectory,

    [int]$RestAbi = 29,

    [int]$DubboAbi = 7,

    [int]$RedisAbi = 6,

    [int]$GlowrootAbi = 6,

    [string]$LinuxMinimumGlibc = "2.17",

    [string]$MacosArm64Binary,

    [string]$MacosArm64Metadata,

    [string]$MacosArm64Checksum,

    [string]$LinuxArm64Binary,

    [string]$LinuxArm64Metadata,

    [string]$LinuxArm64Checksum
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

function Test-OptionalArtifactSet(
    [string]$Platform,
    [string]$Binary,
    [string]$Metadata,
    [string]$Checksum
) {
    $provided = @($Binary, $Metadata, $Checksum) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    if ($provided.Count -eq 0) {
        return $false
    }
    if ($provided.Count -ne 3) {
        throw "$Platform synchronization requires binary, metadata, and checksum together"
    }
    return $true
}

function Read-SourceRevision([string]$Repository) {
    $revision = (& git -C $Repository rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($revision)) {
        throw "Cannot read native source revision from $Repository"
    }
    $dirty = & git -C $Repository status --porcelain
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
$hasMacosArm64 = Test-OptionalArtifactSet "macOS ARM64" `
    $MacosArm64Binary $MacosArm64Metadata $MacosArm64Checksum
$hasLinuxArm64 = Test-OptionalArtifactSet "Linux ARM64" `
    $LinuxArm64Binary $LinuxArm64Metadata $LinuxArm64Checksum
$macosArm64Source = if ($hasMacosArm64) { Resolve-RequiredFile $MacosArm64Binary } else { $null }
$linuxArm64Source = if ($hasLinuxArm64) { Resolve-RequiredFile $LinuxArm64Binary } else { $null }
$sourceRepository = (Resolve-Path -LiteralPath $NativeSourceDirectory).Path
$sourceRevision = Read-SourceRevision $sourceRepository
$resourcesRoot = Join-Path $PSScriptRoot "..\src\main\resources\native"
$resourceRoots = @($resourcesRoot)
if (-not [string]::IsNullOrWhiteSpace($CacheResourcesDirectory)) {
    $resourceRoots += $CacheResourcesDirectory
}
foreach ($platform in @("macos-arm64", "linux-arm64")) {
    $provided = if ($platform -eq "macos-arm64") { $hasMacosArm64 } else { $hasLinuxArm64 }
    foreach ($resourceRoot in $resourceRoots) {
        $existingPlatform = Join-Path $resourceRoot $platform
        if (-not $provided -and (Test-Path -LiteralPath $existingPlatform) -and
                @(Get-ChildItem -LiteralPath $existingPlatform -File).Count -gt 0) {
            throw "Cannot stamp a new global source revision while retaining unverified $platform artifacts. Supply that platform's complete artifact set or use an isolated x64 resource overlay."
        }
    }
}
$sourceRevisionFull = $sourceRevision
$windowsMeta = Read-BuildMetadata $WindowsMetadata "windows-x64-rust_hyper"
$linuxMeta = Read-BuildMetadata $LinuxMetadata "linux-x64-librust_hyper"
$metadata = @($windowsMeta, $linuxMeta)
if ($hasMacosArm64) {
    $macosArm64Meta = Read-BuildMetadata $MacosArm64Metadata "macos-arm64-librust_hyper"
    $metadata += $macosArm64Meta
}
if ($hasLinuxArm64) {
    $linuxArm64Meta = Read-BuildMetadata $LinuxArm64Metadata "linux-arm64-librust_hyper"
    $metadata += $linuxArm64Meta
}
foreach ($buildMetadata in $metadata) {
    if ($buildMetadata.'source.revision' -ne $sourceRevisionFull) {
        throw "Native artifacts were not built from the checked-out revision $sourceRevisionFull"
    }
}
if ($linuxMeta.'glibc.minimum' -ne $LinuxMinimumGlibc) {
    throw "Linux native metadata must declare glibc.minimum=$LinuxMinimumGlibc"
}
if ($hasLinuxArm64 -and $linuxArm64Meta.'glibc.minimum' -ne $LinuxMinimumGlibc) {
    throw "Linux ARM64 native metadata must declare glibc.minimum=$LinuxMinimumGlibc"
}
if ($hasMacosArm64 -and $macosArm64Meta.'glibc.minimum' -ne "not-applicable") {
    throw "macOS ARM64 native metadata must declare glibc.minimum=not-applicable"
}
$verifiedWindowsHash = Assert-ArtifactChecksum $windowsSource $WindowsChecksum
$verifiedLinuxHash = Assert-ArtifactChecksum $linuxSource $LinuxChecksum
$verifiedMacosArm64Hash = if ($hasMacosArm64) {
    Assert-ArtifactChecksum $macosArm64Source $MacosArm64Checksum
} else { $null }
$verifiedLinuxArm64Hash = if ($hasLinuxArm64) {
    Assert-ArtifactChecksum $linuxArm64Source $LinuxArm64Checksum
} else { $null }
$resources = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\src\main\resources\native")).Path
$windowsDirectory = Join-Path $resources "windows-x64"
$linuxDirectory = Join-Path $resources "linux-x64"
$macosArm64Directory = Join-Path $resources "macos-arm64"
$linuxArm64Directory = Join-Path $resources "linux-arm64"
New-Item -ItemType Directory -Force -Path $windowsDirectory, $linuxDirectory | Out-Null

$windowsTarget = Join-Path $windowsDirectory "rust_hyper.dll"
$linuxTarget = Join-Path $linuxDirectory "librust_hyper.so"
Copy-Item -LiteralPath $windowsSource -Destination $windowsTarget -Force
Copy-Item -LiteralPath $linuxSource -Destination $linuxTarget -Force
if ($hasMacosArm64) {
    New-Item -ItemType Directory -Force -Path $macosArm64Directory | Out-Null
    $macosArm64Target = Join-Path $macosArm64Directory "librust_hyper.dylib"
    Copy-Item -LiteralPath $macosArm64Source -Destination $macosArm64Target -Force
}
if ($hasLinuxArm64) {
    New-Item -ItemType Directory -Force -Path $linuxArm64Directory | Out-Null
    $linuxArm64Target = Join-Path $linuxArm64Directory "librust_hyper.so"
    Copy-Item -LiteralPath $linuxArm64Source -Destination $linuxArm64Target -Force
}

$crateVersion = Read-CrateVersion (Join-Path $sourceRepository "Cargo.toml")
$windowsHash = (Get-FileHash -LiteralPath $windowsTarget -Algorithm SHA256).Hash.ToLowerInvariant()
$linuxHash = (Get-FileHash -LiteralPath $linuxTarget -Algorithm SHA256).Hash.ToLowerInvariant()
if ($windowsHash -ne $verifiedWindowsHash -or $linuxHash -ne $verifiedLinuxHash) {
    throw "Native artifact changed while being synchronized"
}
$restBinaries = @{ "windows-x64" = "rust_hyper.dll"; "linux-x64" = "librust_hyper.so" }
$expectedHashes = @{ "windows-x64" = $verifiedWindowsHash; "linux-x64" = $verifiedLinuxHash }
if ($hasMacosArm64) {
    $restBinaries["macos-arm64"] = "librust_hyper.dylib"
    $expectedHashes["macos-arm64"] = $verifiedMacosArm64Hash
}
if ($hasLinuxArm64) {
    $restBinaries["linux-arm64"] = "librust_hyper.so"
    $expectedHashes["linux-arm64"] = $verifiedLinuxArm64Hash
}
$provenanceArguments = @{
    SourceRevision = $sourceRevision
    CrateVersion = $crateVersion
    RestAbi = $RestAbi
    DubboAbi = $DubboAbi
    RedisAbi = $RedisAbi
    GlowrootAbi = $GlowrootAbi
    LinuxMinimumGlibc = $LinuxMinimumGlibc
    ExpectedHashes = $expectedHashes
}
& (Join-Path $PSScriptRoot "write-native-provenance.ps1") @provenanceArguments `
    -ResourcesDirectory $resources -BinaryNames $restBinaries

if (-not [string]::IsNullOrWhiteSpace($CacheResourcesDirectory)) {
    $cacheResources = [System.IO.Path]::GetFullPath($CacheResourcesDirectory)
    $cacheWindowsDirectory = Join-Path $cacheResources "windows-x64"
    $cacheLinuxDirectory = Join-Path $cacheResources "linux-x64"
    New-Item -ItemType Directory -Force -Path $cacheResources, $cacheWindowsDirectory, $cacheLinuxDirectory | Out-Null
    Copy-Item -LiteralPath $windowsSource -Destination (Join-Path $cacheWindowsDirectory "rust_hyper-windows-x64.dll") -Force
    Copy-Item -LiteralPath $linuxSource -Destination (Join-Path $cacheLinuxDirectory "librust_hyper-linux-x64.so") -Force
    if ($hasMacosArm64) {
        $cacheMacosArm64Directory = Join-Path $cacheResources "macos-arm64"
        New-Item -ItemType Directory -Force -Path $cacheMacosArm64Directory | Out-Null
        Copy-Item -LiteralPath $macosArm64Source `
            -Destination (Join-Path $cacheMacosArm64Directory "librust_hyper-macos-arm64.dylib") -Force
    }
    if ($hasLinuxArm64) {
        $cacheLinuxArm64Directory = Join-Path $cacheResources "linux-arm64"
        New-Item -ItemType Directory -Force -Path $cacheLinuxArm64Directory | Out-Null
        Copy-Item -LiteralPath $linuxArm64Source `
            -Destination (Join-Path $cacheLinuxArm64Directory "librust_hyper-linux-arm64.so") -Force
    }
    $cacheBinaries = @{ "windows-x64" = "rust_hyper-windows-x64.dll"; "linux-x64" = "librust_hyper-linux-x64.so" }
    if ($hasMacosArm64) { $cacheBinaries["macos-arm64"] = "librust_hyper-macos-arm64.dylib" }
    if ($hasLinuxArm64) { $cacheBinaries["linux-arm64"] = "librust_hyper-linux-arm64.so" }
    & (Join-Path $PSScriptRoot "write-native-provenance.ps1") @provenanceArguments `
        -ResourcesDirectory $cacheResources -BinaryNames $cacheBinaries
    Write-Host "  cache resources: $cacheResources"
}

Write-Host "Native artifacts synchronized."
Write-Host "  source revision: $sourceRevision"
Write-Host "  Windows SHA-256: $windowsHash"
Write-Host "  Linux SHA-256:   $linuxHash"
if ($hasMacosArm64) { Write-Host "  macOS ARM64 SHA-256: $verifiedMacosArm64Hash" }
if ($hasLinuxArm64) { Write-Host "  Linux ARM64 SHA-256: $verifiedLinuxArm64Hash" }
Write-Host "  manifest:        $(Join-Path $resources 'native-provenance.properties')"
