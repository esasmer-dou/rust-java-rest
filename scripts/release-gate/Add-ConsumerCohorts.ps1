param([Parameter(Mandatory)][object[]]$Rows, [Parameter(Mandatory)][ValidatePattern('^[a-zA-Z0-9-]+$')][string]$Label)
$ErrorActionPreference = 'Stop'
if ($Rows.Count -ne 36) { throw 'Consumer evidence requires 36 original measured rows' }
if (@($Rows | Group-Object Side,Repeat,Endpoint,C | Where-Object Count -ne 1).Count) {
    throw 'Duplicate consumer measurement'
}
foreach ($row in $Rows) {
    if ($row.Side -notin @('fixed','phase7') -or "$($row.Repeat)" -notin @('1','2','3') -or
        $row.Endpoint -notin @('dubbo-title','dubbo-nested','dubbo-db-typed') -or "$($row.C)" -notin @('16','64')) {
        throw 'Unknown consumer cohort'
    }
    $expected = "$Label-r$($row.Repeat)-$($row.Side)"
    if ($null -ne $row.PSObject.Properties['Prefix']) {
        if ($row.Prefix -ne $expected) { throw 'Consumer cohort prefix mismatch' }
        $row
    } else {
        $row | Select-Object *, @{Name='Prefix'; Expression={$expected}}
    }
}
