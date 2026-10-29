[CmdletBinding()]
param(
    [string] $RepositoryRoot = (Split-Path $PSScriptRoot -Parent),
    [string] $ReportPath = 'target/release-version-review.json',
    [string[]] $MarkdownPaths
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'guard-release-version-rewrites.ps1')
$root = [IO.Path]::GetFullPath($RepositoryRoot)
if (-not $PSBoundParameters.ContainsKey('MarkdownPaths')) {
    $MarkdownPaths = @(& git -C $root ls-files -- '*.md')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate tracked Markdown.' }
}
$review = @(foreach ($path in $MarkdownPaths) {
    $fullPath = [IO.Path]::GetFullPath((Join-Path $root $path))
    $relative = [IO.Path]::GetRelativePath($root, $fullPath)
    if ($relative -eq '..' -or $relative -match '^\.\.[/\\]' -or [IO.Path]::IsPathRooted($relative)) {
        throw 'Markdown paths must remain inside the repository.'
    }
    Get-ReleaseVersionClaimReview -Path $fullPath -Text ([IO.File]::ReadAllText($fullPath))
})
if (-not [IO.Path]::IsPathRooted($ReportPath)) { $ReportPath = Join-Path $root $ReportPath }
$ReportPath = [IO.Path]::GetFullPath($ReportPath)
[void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($ReportPath))
Write-ReleaseVersionReviewReport -Review $review -ReportPath $ReportPath
Write-Output "Manual release-claim review: $($review.Count) paragraphs; nonblocking; $ReportPath"
