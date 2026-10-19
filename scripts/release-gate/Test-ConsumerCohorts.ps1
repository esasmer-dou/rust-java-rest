$ErrorActionPreference = 'Stop'
$source = @(foreach ($side in @('fixed','phase7')) { foreach ($repeat in 1..3) {
    foreach ($endpoint in @('dubbo-title','dubbo-nested','dubbo-db-typed')) { foreach ($c in @(16,64)) {
        [pscustomobject]@{Side=$side;Repeat=$repeat;Endpoint=$endpoint;C=$c;UsefulRps='1234.567';P99Ms='17.252';Non2xx='0'}
    }}
}})
$before = $source | ConvertTo-Json -Depth 5
$result = @(& "$PSScriptRoot/Add-ConsumerCohorts.ps1" -Rows $source -Label isolated-consumer)
if ($result.Count -ne 36 -or ($source | ConvertTo-Json -Depth 5) -ne $before) { throw 'Original rows were mutated' }
foreach ($row in $result) {
    if ($row.Prefix -ne "isolated-consumer-r$($row.Repeat)-$($row.Side)") { throw 'Invalid prefix' }
}
$withoutPrefix = @($result | Select-Object Side,Repeat,Endpoint,C,UsefulRps,P99Ms,Non2xx)
if (($withoutPrefix | ConvertTo-Json -Depth 5) -ne $before) { throw 'Measurement values changed' }
$null = & "$PSScriptRoot/Add-ConsumerCohorts.ps1" -Rows $result -Label isolated-consumer
$checks = 2
foreach ($failure in @('missing','duplicate','side','repeat','endpoint','concurrency','prefix')) {
    $rows = @($before | ConvertFrom-Json)
    switch ($failure) {
        missing { $rows = @($rows | Select-Object -Skip 1) }
        duplicate { $rows[0] = $rows[1] }
        side { $rows[0].Side='other' }
        repeat { $rows[0].Repeat=4 }
        endpoint { $rows[0].Endpoint='unknown' }
        concurrency { $rows[0].C=512 }
        prefix { $rows[0] | Add-Member NoteProperty Prefix wrong }
    }
    $rejected=$false
    try { $null = & "$PSScriptRoot/Add-ConsumerCohorts.ps1" -Rows $rows -Label isolated-consumer } catch { $rejected=$true }
    if (-not $rejected) { throw "Invalid evidence accepted: $failure" }
    $checks++
}
Write-Output "$checks consumer metadata checks passed; original metric values preserved"
