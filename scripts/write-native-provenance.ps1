[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ResourcesDirectory,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string]$SourceRevision,
    [Parameter(Mandatory = $true)]
    [string]$CrateVersion,
    [int]$RestAbi = 29,
    [int]$DubboAbi = 7,
    [int]$RedisAbi = 6,
    [int]$GlowrootAbi = 6,
    [string]$LinuxMinimumGlibc = "2.17",
    [hashtable]$BinaryNames = @{
        "windows-x64" = "rust_hyper.dll"
        "linux-x64" = "librust_hyper.so"
        "linux-arm64" = "librust_hyper.so"
        "macos-arm64" = "librust_hyper.dylib"
    },
    [hashtable]$ExpectedHashes = @{},
    [switch]$SidecarsOnly,
    [switch]$PassThru
)

$ErrorActionPreference = "Stop"

function ConvertTo-CanonicalFeatures([string]$Value) {
    # Runtime build info uses Rust identifiers; published metadata uses Cargo feature names.
    return (($Value -split ',' | ForEach-Object { $_.Replace('_', '-') } |
        Sort-Object -Unique) -join ',')
}

$resources = (Resolve-Path -LiteralPath $ResourcesDirectory).Path
$platforms = @("windows-x64", "linux-x64", "linux-arm64", "macos-arm64") |
    Where-Object { $BinaryNames.ContainsKey($_) }
if ($platforms.Count -eq 0 -or $platforms.Count -ne $BinaryNames.Count) {
    throw "Supply at least one supported platform binary name"
}
if ([string]::IsNullOrWhiteSpace($CrateVersion) -or $CrateVersion -match '[\r\n]' -or
        $RestAbi -le 0 -or $DubboAbi -le 0 -or $RedisAbi -le 0 -or $GlowrootAbi -le 0) {
    throw "Crate version and positive ABI values are required"
}
$hashes = @{}
$features = $null
foreach ($platform in $platforms) {
    $name = $BinaryNames[$platform]
    if ([System.IO.Path]::GetFileName($name) -ne $name) {
        throw "Expected a binary file name for $platform"
    }
    $binary = Join-Path (Join-Path $resources $platform) $name
    $bytes = [System.IO.File]::ReadAllBytes($binary)
    if ($bytes.Length -eq 0) { throw "Native binary is empty: $binary" }
    $digest = [System.Security.Cryptography.SHA256]::Create()
    try {
        $hash = [BitConverter]::ToString($digest.ComputeHash($bytes)).Replace("-", "").ToLowerInvariant()
    } finally {
        $digest.Dispose()
    }
    if ($ExpectedHashes.Count -gt 0 -and
            (-not $ExpectedHashes.ContainsKey($platform) -or $ExpectedHashes[$platform] -ne $hash)) {
        throw "Native artifact hash differs from trusted input for $platform"
    }
    $embedded = [System.Text.Encoding]::ASCII.GetString($bytes)
    if (-not $embedded.Contains($SourceRevision)) {
        throw "Native binary does not contain the trusted source revision: $platform"
    }
    $found = @([regex]::Matches($embedded, 'features=([a-z0-9_,\-]+)') |
        ForEach-Object { ConvertTo-CanonicalFeatures $_.Groups[1].Value } | Sort-Object -Unique)
    if ($found.Count -ne 1 -or ($null -ne $features -and $features -ne $found[0])) {
        throw "Native build features are missing, ambiguous, or differ across platforms: $platform"
    }
    $features = $found[0]
    $hashes[$platform] = $hash
}

$common = @(
    "schema=2"
    "rest.abi=$RestAbi"
    "dubbo.abi=$DubboAbi"
    "redis.abi=$RedisAbi"
    "glowroot.abi=$GlowrootAbi"
    "crate.version=$CrateVersion"
    "source.revision=$SourceRevision"
    "features=$features"
    "distribution.scope=current-source-compile-verified"
    "runtime.verified=false"
)
$platformLines = @{}
foreach ($platform in $platforms) {
    $lines = @("$platform.sha256=$($hashes[$platform])")
    if ($platform.StartsWith("linux-")) {
        $lines += "$platform.glibc-minimum=$LinuxMinimumGlibc"
    }
    $platformLines[$platform] = $lines
}

# Validate all input bytes before producing any manifest; never infer revision from checkout HEAD.
$outputs = [ordered]@{}
if (-not $SidecarsOnly) {
    $rootLines = @($common)
    foreach ($platform in $platforms) { $rootLines += $platformLines[$platform] }
    $outputs[(Join-Path $resources "native-provenance.properties")] = ($rootLines -join "`n") + "`n"
}
foreach ($platform in $platforms) {
    $path = Join-Path (Join-Path $resources $platform) "native-provenance.properties"
    $outputs[$path] = (($common + $platformLines[$platform]) -join "`n") + "`n"
}
if ($PassThru) {
    foreach ($output in $outputs.GetEnumerator()) {
        [pscustomobject]@{ Path = $output.Key; Content = $output.Value }
    }
} else {
    foreach ($output in $outputs.GetEnumerator()) {
        [System.IO.File]::WriteAllText($output.Key, $output.Value, [System.Text.UTF8Encoding]::new($false))
        Write-Host "Native provenance generated: $($output.Key)"
    }
}
