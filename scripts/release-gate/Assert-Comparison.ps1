[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$SummaryPath,
    [Parameter(Mandatory)][ValidateSet('cache', 'writer', 'consumer')][string]$Lane
)

$ErrorActionPreference = 'Stop'
$summary = Get-Content -LiteralPath $SummaryPath -Raw | ConvertFrom-Json
$required = if ($Lane -eq 'writer') {
    @('CpuPass', 'LatencyPass', 'MemoryPass')
} else {
    @('CorrectnessPass', 'PerformancePass', 'StrictPerformancePass', 'MemoryPass')
}
foreach ($name in $required) {
    $property = $summary.PSObject.Properties[$name]
    if ($null -eq $property -or $property.Value -isnot [bool] -or -not $property.Value) {
        throw "$Lane release comparison failed or has no boolean PASS: $name ($SummaryPath)"
    }
}
if ($Lane -eq 'writer') {
    if ($summary.Runs -ne 6 -or $summary.MeasuredPublications -ne 1200) {
        throw 'Writer gate requires three fresh pairs and 200 measured publications per JVM'
    }
} else {
    $expected = if ($Lane -eq 'cache') { 12 } else { 36 }
    if ($summary.Trials -ne $expected -or $summary.Requests -le 0) {
        throw "$Lane gate has an incomplete trial count"
    }
}
Write-Output "${Lane}: complete strict comparison passed"
