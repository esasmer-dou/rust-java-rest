[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('cache', 'writer', 'consumer')][string]$Lane,
    [Parameter(Mandatory)][string]$BundleDirectory
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$root = (Resolve-Path -LiteralPath $BundleDirectory).Path
$manifest = Get-Content (Join-Path $root 'contract.json') -Raw | ConvertFrom-Json
if ($manifest.Schema -ne 1 -or $manifest.Baseline -ne 'phase2' -or $manifest.Candidate -ne 'phase7' -or
    $manifest.CandidateJvmOptions -ne '' -or $manifest.Repeats -ne 3) {
    throw 'Unexpected frozen comparison contract; JVM experiments are not release candidates'
}
foreach ($file in $manifest.Files) {
    $actual = (Get-FileHash -LiteralPath (Join-Path $root $file.Path) -Algorithm SHA256).Hash
    if ($actual -ne $file.Sha256) { throw "Bundle checksum mismatch: $($file.Path)" }
}
foreach ($image in $manifest.Images) {
    $actual = & docker image inspect $image.Name --format '{{.Id}}'
    if ($LASTEXITCODE -ne 0 -or $actual -ne $image.Id) { throw "Image mismatch: $($image.Name)" }
}
function Invoke-Docker([string[]]$Arguments) {
    & docker @Arguments | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Docker failed: $($Arguments[0])" }
}
$existing = @(& docker ps --format '{{.Names}}')
if ($LASTEXITCODE -ne 0 -or $existing.Count -ne 0) { throw 'A fresh isolated Docker host is required' }
$runtime = $manifest.RuntimeOverride
if ($null -eq $runtime -or $runtime.SourceRevision -notmatch '^[0-9a-f]{40}$' -or
    $runtime.Path -ne 'librust_hyper.so') { throw 'A clean, pinned native runtime is required' }
$context = Join-Path $root 'native-override'
$null = New-Item -ItemType Directory -Path $context -Force
Copy-Item -LiteralPath (Join-Path $root $runtime.Path) -Destination $context
foreach ($image in $manifest.Images | Where-Object { $_.Name -match ':phase7$' }) {
    if ($image.Id -notmatch '^sha256:[0-9a-f]{64}$') { throw 'Invalid base image identity' }
    # BuildKit resolves FROM by a local tag, not a bare config-image SHA.
    # The tag's immutable ID was checked above on this exclusive runner.
    @("FROM $($image.Name)", 'COPY librust_hyper.so /app/native/librust_hyper.so') |
        Set-Content (Join-Path $context 'Dockerfile')
    Invoke-Docker @('build', '--pull=false', '--network=none', '-t', $image.Name, $context)
    $actual = & docker run --rm --entrypoint sha256sum $image.Name /app/native/librust_hyper.so
    if ($LASTEXITCODE -ne 0 -or ($actual -split '\s+')[0] -ne $runtime.Sha256) {
        throw "Candidate does not contain the pinned clean runtime: $($image.Name)"
    }
}
$harness = Join-Path $root 'regression-phase2-20261019'
$label = "isolated-$Lane"
try {
    Invoke-Docker @('network', 'create', 'functional-plan-20261018')
    Invoke-Docker @('run', '-d', '--name', 'fp-db', '--network', 'functional-plan-20261018', '--cpus=1', '--memory=256m',
        '-e', 'POSTGRES_DB=reactor_sample', '-e', 'POSTGRES_USER=reactor', '-e', 'POSTGRES_PASSWORD=local-test-only', 'postgres:16-alpine')
    Invoke-Docker @('run', '-d', '--name', 'fp-redis', '--network', 'functional-plan-20261018', '--cpus=1', '--memory=96m', 'redis:7.4-alpine')
    $ready = $false
    foreach ($attempt in 1..45) {
        & docker exec fp-db pg_isready -U reactor -d reactor_sample 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Database fixture did not become ready' }
    switch ($Lane) {
        'cache' {
            & (Join-Path $harness 'cache-pairs.ps1') -Candidate phase7 -BaselineImageTag phase2 -Label $label
            & (Join-Path $harness 'summarize-native-paired.ps1') -Candidate phase7 -Label $label -Kind cache
        }
        'writer' {
            $warmRounds = if ($null -eq $manifest.WriterWarmRounds) { 40 } else { [int]$manifest.WriterWarmRounds }
            if ($warmRounds -notin @(40, 80)) { throw 'Unsupported writer warmup contract' }
            & (Join-Path $harness 'writer-pairs.ps1') -Candidate phase7 -Baseline phase2 -Repeats 3 -Label $label -WarmRounds $warmRounds
            & (Join-Path $harness 'summarize-writer.ps1') -Candidate phase7 -Baseline phase2 -Repeats 3 -Label $label
            $summaryPath = Join-Path $harness "$label-summary.json"
            $result = Get-Content $summaryPath -Raw | ConvertFrom-Json
            $result | Add-Member NoteProperty WarmupPublicationsPerJvm ($warmRounds * 5)
            $result.Note = $result.Note.Replace('200 warmup publications', "$($warmRounds * 5) warmup publications")
            $result | ConvertTo-Json -Depth 7 | Set-Content $summaryPath
        }
        'consumer' {
            Invoke-Docker @('run', '-d', '--name', 'fp-provider', '--network', 'functional-plan-20261018', '--cpus=1', '--memory=192m',
                '-e', 'DUBBO_PROVIDER_HOST=fp-provider', '-e', 'DUBBO_PROVIDER_BIND_HOST=0.0.0.0',
                '-e', 'SAMPLE_DB_JDBC_URL=jdbc:postgresql://fp-db:5432/reactor_sample', '-e', 'SAMPLE_DB_PASSWORD=local-test-only',
                '-e', 'SAMPLE_DB_INITIALIZATION_FAIL_TIMEOUT_MS=30000', 'fp-rest-sample-dubbo-provider:fixed',
                'com.reactor.sample.dubbo.provider.app.RestSampleDubboProviderApplication')
            $ready = $false
            foreach ($attempt in 1..60) {
                & docker exec fp-provider bash -lc 'timeout 1 bash -c "echo > /dev/tcp/127.0.0.1/20880"' 2>$null | Out-Null
                if ($LASTEXITCODE -eq 0) { $ready = $true; break }
                Start-Sleep -Seconds 1
            }
            if (-not $ready) { throw 'Provider fixture did not become ready' }
            & (Join-Path $harness 'native-pairs.ps1') -Candidate phase7 -BaselineImageTag phase2 -Label $label -NoPause `
                -QuietSeconds 5 -JvmExtraOptions '-XX:-TransparentHugePage'
            & (Join-Path $harness 'summarize-native-paired.ps1') -Candidate phase7 -Label $label -Kind dubbo
        }
    }
    & (Join-Path $PSScriptRoot 'Assert-Comparison.ps1') -SummaryPath (Join-Path $harness "$label-summary.json") -Lane $Lane
} finally {
    $names = @(& docker ps -a --format '{{.Names}}')
    foreach ($name in $names) {
        if ($name -match '^fp-') { & docker rm -f $name 2>$null | Out-Null }
    }
}
