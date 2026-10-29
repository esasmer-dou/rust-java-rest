# Dot-source this helper before applying version replacements to documentation.
Set-StrictMode -Version Latest
$script:ReleaseVersionClaimPattern = '(?i)\b(?:unpublished|published|publishing|publication|unreleased|released)\b|not\s+(?:yet\s+)?released|source[\s/-]+(?:checkout|only|preview|release)|kaynak[\s-]+(?:kod|depo|[oö]nizleme)|yay[ıi]ml|hen[uü]z\s+yay'

function Get-ReleaseVersionClaimReview {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string] $Path,
        [Parameter(Mandatory)][AllowEmptyString()][string] $Text
    )
    $line = 1
    foreach ($part in [regex]::Split($Text, '((?:\r?\n)[ \t]*(?:\r?\n))')) {
        if ($part -match $script:ReleaseVersionClaimPattern) {
            [pscustomobject]@{
                file = $Path
                line = $line
                old_sentence = $part
                proposed_sentence = $null
                decision = 'manual-review'
                reason = 'Verify artifact evidence before changing this claim; no automatic rewrite.'
            }
        }
        $line += [regex]::Matches($part, '\n').Count
    }
}

function Get-GuardedReleaseVersionUpdate {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string] $Path,
        [Parameter(Mandatory)][AllowEmptyString()][string] $Text,
        [Parameter(Mandatory)][AllowEmptyCollection()][hashtable] $Replacements
    )

    foreach ($key in $Replacements.Keys) {
        if ([string]::IsNullOrEmpty([string] $key) -or $key -match '[\r\n]') {
            throw 'Replacement keys must be non-empty, single-line literals.'
        }
        if ($Replacements[$key] -isnot [string] -or $Replacements[$key] -match '[\r\n]') {
            throw 'Replacement values must be single-line strings.'
        }
    }
    $review = [Collections.Generic.List[object]]::new()
    if ($Replacements.Count -eq 0) {
        return [pscustomobject]@{ Text = $Text; ManualReview = @() }
    }
    $pattern = ($Replacements.Keys | Sort-Object { ([string] $_).Length } -Descending |
        ForEach-Object { [regex]::Escape([string] $_) }) -join '|'
    $replace = [Text.RegularExpressions.MatchEvaluator] {
        param($match)
        return $Replacements[$match.Value]
    }
    # Protect whole paragraphs, including claims wrapped across Markdown lines.
    $parts = [regex]::Split($Text, '((?:\r?\n)[ \t]*(?:\r?\n))')
    $line = 1
    for ($i = 0; $i -lt $parts.Length; $i++) {
        $old = $parts[$i]
        $candidate = [regex]::Replace($old, $pattern, $replace)
        if ($candidate -cne $old) {
            if ($old -match $script:ReleaseVersionClaimPattern) {
                $first = [regex]::Match($old, $pattern)
                $review.Add([pscustomobject]@{
                    file = $Path
                    line = $line + [regex]::Matches($old.Substring(0, $first.Index), '\n').Count
                    old_sentence = $old
                    proposed_sentence = $candidate
                    decision = 'manual-review'
                    reason = 'Publication/source-checkout claims require artifact evidence; left unchanged.'
                })
            } else {
                $parts[$i] = $candidate
            }
        }
        $line += [regex]::Matches($old, '\n').Count
    }
    return [pscustomobject]@{ Text = ($parts -join ''); ManualReview = @($review.ToArray()) }
}

function Write-ReleaseVersionReviewReport {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][AllowEmptyCollection()][object[]] $Review,
        [Parameter(Mandatory)][string] $ReportPath
    )
    $report = [ordered]@{
        schema = 1
        blocking = $false
        manual_review_required = ($Review.Count -gt 0)
        matches = @($Review)
    }
    [IO.File]::WriteAllText([IO.Path]::GetFullPath($ReportPath),
        ($report | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    # Review findings never throw, exit, or turn a release build red.
}
