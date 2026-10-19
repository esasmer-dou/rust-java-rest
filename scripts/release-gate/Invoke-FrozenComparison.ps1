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
            & (Join-Path $harness 'writer-pairs.ps1') -Candidate phase7 -Baseline phase2 -Repeats 3 -Label $label
            & (Join-Path $harness 'summarize-writer.ps1') -Candidate phase7 -Baseline phase2 -Repeats 3 -Label $label
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
