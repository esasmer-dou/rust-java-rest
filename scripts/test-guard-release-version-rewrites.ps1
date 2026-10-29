param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'guard-release-version-rewrites.ps1')

$script:checks = 0
function Assert-Equal($Expected, $Actual, [string] $Message) {
    if ($Expected -cne $Actual) { throw $Message }
    $script:checks++
}

$versions = @{ '4.7.1' = '4.8.0'; '4.8.0' = '4.8.1' }
foreach ($claim in @(
    'Published 4.8.0 includes this feature.',
    'This is unpublished in 4.8.0.',
    'Unreleased 4.8.0 source feature.',
    'It has not yet been released in 4.8.0.',
    "The source`ncheckout uses 4.8.0.",
    'This source-only feature uses 4.8.0.',
    'The source/release set is 4.8.0.',
    '4.8.0 ile yayımlanmıştır.',
    '4.8.0 henüz yayımlanmadı.',
    "Kaynak kod önizlemesi`n4.8.0 kullanır."
)) {
    $result = Get-GuardedReleaseVersionUpdate -Path 'README.md' -Text $claim -Replacements $versions
    Assert-Equal $claim $result.Text 'A protected claim was changed.'
    Assert-Equal 1 $result.ManualReview.Count 'A protected claim was not reported.'
}

$text = "Published 4.8.0.`r`n`r`n<version>4.7.1</version>`r`n<version>4.8.0</version>`r`n"
$result = Get-GuardedReleaseVersionUpdate -Path 'README.md' -Text $text -Replacements $versions
Assert-Equal "Published 4.8.0.`r`n`r`n<version>4.8.0</version>`r`n<version>4.8.1</version>`r`n" $result.Text 'Safe updates cascaded or changed line endings.'
Assert-Equal 1 $result.ManualReview.Count 'Expected one nonblocking review item.'
Assert-Equal 1 $result.ManualReview[0].line 'Wrong review line.'
Assert-Equal $text.Split("`r")[0] $result.ManualReview[0].old_sentence 'The original claim was not retained.'

$result = Get-GuardedReleaseVersionUpdate -Path 'pom.xml' -Text '<version>4.8.0</version>' -Replacements $versions
Assert-Equal '<version>4.8.1</version>' $result.Text 'Safe POM-only alignment stopped working.'
Assert-Equal 0 $result.ManualReview.Count 'Safe XML required manual review.'
$empty = Get-GuardedReleaseVersionUpdate -Path 'empty.md' -Text '' -Replacements @{}
Assert-Equal '' $empty.Text 'Empty input changed.'

$reportPath = Join-Path ([IO.Path]::GetTempPath()) ('release-review-' + [guid]::NewGuid() + '.json')
try {
    $protected = Get-GuardedReleaseVersionUpdate -Path 'README.md' -Text 'Published 4.8.0.' -Replacements $versions
    Write-ReleaseVersionReviewReport -Review $protected.ManualReview -ReportPath $reportPath
    $report = Get-Content -LiteralPath $reportPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-Equal $false $report.blocking 'Review report became blocking.'
    Assert-Equal $true $report.manual_review_required 'Review report lost its finding.'
    Assert-Equal 'Published 4.8.0.' $report.matches[0].old_sentence 'Report lost original text.'
} finally {
    if (Test-Path -LiteralPath $reportPath) { Remove-Item -LiteralPath $reportPath }
}

$fixture = Join-Path ([IO.Path]::GetTempPath()) ('release-review-fixture-' + [guid]::NewGuid())
try {
    [void][IO.Directory]::CreateDirectory($fixture)
    $docPath = Join-Path $fixture 'README.md'
    $doc = "Published 4.8.0.`n`nSource checkout`nuses 4.8.0."
    [IO.File]::WriteAllText($docPath, $doc)
    & (Join-Path $PSScriptRoot 'review-release-version-claims.ps1') -RepositoryRoot $fixture -MarkdownPaths 'README.md'
    $report = Get-Content -LiteralPath (Join-Path $fixture 'target/release-version-review.json') -Raw | ConvertFrom-Json
    Assert-Equal 2 $report.matches.Count 'Workflow scanner missed a wrapped claim.'
    Assert-Equal $false $report.blocking 'Workflow scanner became blocking.'
    Assert-Equal $doc ([IO.File]::ReadAllText($docPath)) 'Workflow scanner rewrote documentation.'
} finally {
    if (Test-Path -LiteralPath $fixture) { Remove-Item -LiteralPath $fixture -Recurse }
}
Write-Output "PASS: $script:checks release-version guard checks"
