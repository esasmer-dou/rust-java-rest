$ErrorActionPreference = 'Stop'
$directory = Join-Path ([IO.Path]::GetTempPath()) ('release-gate-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $directory
$path = Join-Path $directory 'summary.json'
$checks = 0
function Check($value, [string]$lane, [bool]$expected) {
    $value | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $path
    $passed = $false
    try {
        & (Join-Path $PSScriptRoot 'Assert-Comparison.ps1') -SummaryPath $path -Lane $lane | Out-Null
        $passed = $true
    } catch {}
    if ($passed -ne $expected) { throw "Unexpected gate decision: $lane / $($value | ConvertTo-Json -Compress)" }
    $script:checks++
}
try {
    foreach ($lane in @('cache', 'consumer')) {
        $valid = @{CorrectnessPass=$true; PerformancePass=$true; StrictPerformancePass=$true;
            MemoryPass=$true; Trials=$(if ($lane -eq 'cache') {12} else {36}); Requests=1000}
        Check $valid $lane $true
        foreach ($flag in @('CorrectnessPass', 'PerformancePass', 'StrictPerformancePass', 'MemoryPass')) {
            foreach ($bad in @($false, 'true', $null, 1)) {
                $copy = $valid.Clone(); $copy[$flag] = $bad
                Check $copy $lane $false
            }
            $copy = $valid.Clone(); $copy.Remove($flag)
            Check $copy $lane $false
        }
        $copy = $valid.Clone(); $copy.Trials--; Check $copy $lane $false
        $copy = $valid.Clone(); $copy.Requests=0; Check $copy $lane $false
    }
    $valid = @{CpuPass=$true; LatencyPass=$true; MemoryPass=$true; Runs=6; MeasuredPublications=1200}
    Check $valid 'writer' $true
    foreach ($flag in @('CpuPass', 'LatencyPass', 'MemoryPass')) {
        foreach ($bad in @($false, 'true', $null, 1)) {
            $copy = $valid.Clone(); $copy[$flag] = $bad; Check $copy 'writer' $false
        }
        $copy = $valid.Clone(); $copy.Remove($flag); Check $copy 'writer' $false
    }
    $copy = $valid.Clone(); $copy.Runs=4; Check $copy 'writer' $false
    $copy = $valid.Clone(); $copy.MeasuredPublications=1199; Check $copy 'writer' $false
    Set-Content -LiteralPath $path -Value '{not-json'
    $rejected = $false
    try { & (Join-Path $PSScriptRoot 'Assert-Comparison.ps1') $path cache | Out-Null } catch { $rejected=$true }
    if (-not $rejected) { throw 'Invalid JSON was accepted' }
    $checks++
    Write-Output "$checks fail-closed gate checks passed"
} finally {
    Remove-Item -LiteralPath $path -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $directory
}
