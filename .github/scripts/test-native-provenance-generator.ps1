[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$generator = Join-Path $PSScriptRoot "../../scripts/write-native-provenance.ps1"
$fixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("native-provenance-" + [guid]::NewGuid().ToString('N'))
$revision = "3418671b5010afb5dc049897f2a7287dc6a58daf"
$canonical = "dubbo,edge-cache,edge-jwt,glowroot,outbound-http,redis,shared-slab,websocket"
$runtime = "websocket,shared_slab,redis,outbound_http,glowroot,edge_jwt,edge_cache,dubbo"
$binaryNames = @{
    "windows-x64" = "rust_hyper.dll"
    "linux-x64" = "librust_hyper.so"
    "linux-arm64" = "librust_hyper.so"
    "macos-arm64" = "librust_hyper.dylib"
}
$arguments = @{
    ResourcesDirectory = $fixtureRoot
    SourceRevision = $revision
    CrateVersion = "0.1.0"
    BinaryNames = $binaryNames
    PassThru = $true
}

try {
    foreach ($platform in $binaryNames.Keys) {
        $directory = Join-Path $fixtureRoot $platform
        [void][System.IO.Directory]::CreateDirectory($directory)
        $features = if ($platform -eq "linux-arm64") { $canonical } else { $runtime }
        $payload = "$revision`nfeatures=$features`n"
        if ($platform -eq "macos-arm64") { $payload += "features=$canonical`n" }
        [System.IO.File]::WriteAllBytes((Join-Path $directory $binaryNames[$platform]),
            [System.Text.Encoding]::ASCII.GetBytes($payload))
    }
    $outputs = @(& $generator @arguments)
    if ($outputs.Count -ne 5) { throw "Expected root and four platform sidecars" }
    $expected = @{
        schema = "2"; "rest.abi" = "29"; "dubbo.abi" = "7"; "redis.abi" = "6"; "glowroot.abi" = "6"
        "crate.version" = "0.1.0"; "source.revision" = $revision; features = $canonical
        "distribution.scope" = "current-source-compile-verified"; "runtime.verified" = "false"
    }
    foreach ($output in $outputs) {
        $metadata = ConvertFrom-StringData $output.Content
        $parent = Split-Path -Parent $output.Path
        foreach ($key in $expected.Keys) {
            if ($metadata[$key] -cne $expected[$key]) { throw "Unexpected $key in $($output.Path)" }
        }
        foreach ($platform in $binaryNames.Keys) {
            if ($parent -eq $fixtureRoot -or (Split-Path -Leaf $parent) -eq $platform) {
                $binary = Join-Path (Join-Path $fixtureRoot $platform) $binaryNames[$platform]
                $hash = (Get-FileHash -LiteralPath $binary -Algorithm SHA256).Hash.ToLowerInvariant()
                if ($metadata["$platform.sha256"] -cne $hash) { throw "Incorrect hash: $platform" }
                if ($platform.StartsWith("linux-") -and $metadata["$platform.glibc-minimum"] -ne "2.17") {
                    throw "Incorrect glibc minimum: $platform"
                }
            }
        }
    }
    $macosBinary = Join-Path (Join-Path $fixtureRoot "macos-arm64") $binaryNames["macos-arm64"]
    [System.IO.File]::WriteAllBytes($macosBinary,
        [System.Text.Encoding]::ASCII.GetBytes("$revision`nfeatures=redis`n"))
    $rejected = $false
    try { & $generator @arguments | Out-Null } catch {
        if ($_.Exception.Message -notlike "Native build features are missing, ambiguous, or differ across platforms:*") {
            throw
        }
        $rejected = $true
    }
    if (-not $rejected) { throw "Different feature sets were accepted" }
    Write-Host "Native provenance generator: canonical root/sidecars, semantic aliases, hashes and mismatch rejection OK"
} finally {
    if (Test-Path -LiteralPath $fixtureRoot) {
        Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
    }
}
