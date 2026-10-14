[CmdletBinding()]
param(
    [string]$WorkspaceRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
)

$ErrorActionPreference = 'Stop'

$workspaceRoot = (Resolve-Path -LiteralPath $WorkspaceRoot).Path
$platformParentPom = Join-Path $workspaceRoot 'rust-java-rest/platform/parent/pom.xml'

function Read-MavenProject {
    param(
        [Parameter(Mandatory)]
        [string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Maven POM not found: $Path"
    }

    [xml]$document = Get-Content -LiteralPath $Path -Raw
    $namespace = [System.Xml.XmlNamespaceManager]::new($document.NameTable)
    $namespace.AddNamespace('m', 'http://maven.apache.org/POM/4.0.0')

    return @{
        Document = $document
        Namespace = $namespace
    }
}

$platform = Read-MavenProject -Path $platformParentPom
$platformVersionNode = $platform.Document.SelectSingleNode(
    '/m:project/m:version',
    $platform.Namespace
)
if ($null -eq $platformVersionNode -or [string]::IsNullOrWhiteSpace($platformVersionNode.InnerText)) {
    throw "Platform parent version is missing: $platformParentPom"
}
$expectedVersion = $platformVersionNode.InnerText.Trim()

$samplePoms = Get-ChildItem -LiteralPath $workspaceRoot -Directory |
    Where-Object { $_.Name -like 'rest-sample-*' -or $_.Name -like 'rust-sample-*' } |
    ForEach-Object { Join-Path $_.FullName 'pom.xml' } |
    Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
    Sort-Object

$checked = 0
$mismatches = [System.Collections.Generic.List[string]]::new()
foreach ($pom in $samplePoms) {
    $sample = Read-MavenProject -Path $pom
    $parentArtifactNode = $sample.Document.SelectSingleNode(
        '/m:project/m:parent/m:artifactId',
        $sample.Namespace
    )
    if ($null -eq $parentArtifactNode -or $parentArtifactNode.InnerText.Trim() -ne 'rust-java-platform-parent') {
        continue
    }

    $checked++
    $parentVersionNode = $sample.Document.SelectSingleNode(
        '/m:project/m:parent/m:version',
        $sample.Namespace
    )
    $actualVersion = if ($null -eq $parentVersionNode) { '<missing>' } else { $parentVersionNode.InnerText.Trim() }
    $relativePom = $pom.Substring($workspaceRoot.Length).TrimStart([char[]]@('\', '/'))

    if ($actualVersion -ne $expectedVersion) {
        $mismatches.Add("$relativePom expected=$expectedVersion actual=$actualVersion")
    } else {
        Write-Host "[OK] $relativePom -> $actualVersion"
    }
}

if ($checked -eq 0) {
    Write-Error 'No sample project using rust-java-platform-parent was found.'
    exit 1
}

if ($mismatches.Count -gt 0) {
    $mismatches | ForEach-Object { Write-Error "[MISMATCH] $_" }
    exit 1
}

Write-Host "Version alignment verified: $checked sample project(s) use rust-java-platform-parent $expectedVersion."
exit 0
