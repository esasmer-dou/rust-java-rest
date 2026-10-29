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

function Assert-Version([string]$Label, [string]$Actual, [string]$Expected) {
    if ($Actual -ne $Expected) {
        $mismatches.Add("$Label expected=$Expected actual=$Actual")
    } else {
        Write-Host "[OK] $Label -> $Actual"
    }
}

$core = Read-MavenProject -Path (Join-Path $workspaceRoot 'rust-java-rest/pom.xml')
Assert-Version 'REST core' ([string]$core.Document.project.version) $expectedVersion
$bom = Read-MavenProject -Path (Join-Path $workspaceRoot 'rust-java-rest/platform/bom/pom.xml')
Assert-Version 'Platform BOM' ([string]$bom.Document.project.version) $expectedVersion
$dependencyVersions = @{
    'rust-java-rest.version' = $expectedVersion
}
foreach ($name in @('java-rust-dubbo', 'java-rust-cache', 'rust-sample-model', 'rest-sample-utility')) {
    $project = Read-MavenProject -Path (Join-Path $workspaceRoot "$name/pom.xml")
    $dependencyVersions["$name.version"] = [string]$project.Document.project.version
    $restVersion = $project.Document.SelectSingleNode(
        '/m:project/m:properties/m:rust-java-rest.version', $project.Namespace)
    if ($null -ne $restVersion) {
        Assert-Version "$name REST dependency" $restVersion.InnerText.Trim() $expectedVersion
    }
}
foreach ($entry in $dependencyVersions.GetEnumerator()) {
    foreach ($owner in @(@{ Name = 'parent'; Project = $platform }, @{ Name = 'BOM'; Project = $bom })) {
        $node = $owner.Project.Document.SelectSingleNode(
            "/m:project/m:properties/m:$($entry.Key)", $owner.Project.Namespace)
        if ($null -ne $node) {
            Assert-Version "$($owner.Name) $($entry.Key)" $node.InnerText.Trim() $entry.Value
        } elseif ($entry.Key -in @('rust-java-rest.version', 'java-rust-dubbo.version', 'java-rust-cache.version')) {
            $mismatches.Add("$($owner.Name) is missing $($entry.Key)")
        }
    }
}
foreach ($pom in $samplePoms) {
    $sample = Read-MavenProject -Path $pom
    $parentArtifactNode = $sample.Document.SelectSingleNode(
        '/m:project/m:parent/m:artifactId',
        $sample.Namespace
    )
    if ($null -eq $parentArtifactNode -or $parentArtifactNode.InnerText.Trim() -ne 'rust-java-platform-parent') {
        # Shared utility has no platform parent, but its model dependency must still agree.
        $modelVersion = $sample.Document.SelectSingleNode(
            '/m:project/m:properties/m:rust-sample-model.version', $sample.Namespace)
        if ($null -ne $modelVersion) {
            Assert-Version ([IO.Path]::GetFileName((Split-Path $pom)) + ' model') `
                $modelVersion.InnerText.Trim() $dependencyVersions['rust-sample-model.version']
        }
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

    $properties = @{}
    foreach ($property in $dependencyVersions.GetEnumerator()) { $properties[$property.Key] = $property.Value }
    foreach ($property in $sample.Document.project.properties.ChildNodes) {
        if ($property.NodeType -eq [System.Xml.XmlNodeType]::Element) {
            $properties[$property.LocalName] = $property.InnerText.Trim()
        }
    }
    $coordinates = $sample.Document.SelectNodes(
        '//m:dependency[m:groupId="com.reactor" or m:groupId="com.reactor.sample"] | //m:annotationProcessorPaths/m:path',
        $sample.Namespace)
    foreach ($dependency in $coordinates) {
        $artifact = [string]$dependency.artifactId
        if ($artifact -match '^\$\{([^}]+)\}$') {
            $propertyName = $Matches[1]
            $artifact = [string]$properties[$propertyName]
            if ([string]::IsNullOrWhiteSpace($artifact)) {
                $mismatches.Add("$relativePom has an unresolved artifactId property: $propertyName")
                continue
            }
        }
        $key = if ($artifact.StartsWith('rust-java-')) { 'rust-java-rest.version' } else { "$artifact.version" }
        if (-not $dependencyVersions.ContainsKey($key)) { continue }
        $version = [string]$dependency.version
        if ([string]::IsNullOrWhiteSpace($version)) { $version = $dependencyVersions[$key] }
        elseif ($version -match '^\$\{([^}]+)\}$') { $version = [string]$properties[$Matches[1]] }
        Assert-Version "$relativePom $artifact" $version $dependencyVersions[$key]
    }
}

$generatorPath = Join-Path $workspaceRoot 'rust-java-rest/src/main/java/com/reactor/rust/codegen/ProjectGenerator.java'
$generator = Get-Content -Raw -LiteralPath $generatorPath
$template = Join-Path $workspaceRoot 'rust-java-rest/src/codegen/resources/com/reactor/rust/codegen/generator-version.properties'
if ($generator -match 'REST_VERSION\s*=\s*"' -or $generator -notmatch 'generator-version\.properties' `
        -or -not (Test-Path -LiteralPath $template) `
        -or (Get-Content -Raw -LiteralPath $template).Trim() -ne 'framework.version=${project.version}') {
    $mismatches.Add('ProjectGenerator must use the filtered Maven project.version resource, not a version literal')
} else {
    Write-Host '[OK] ProjectGenerator uses the filtered build-version contract'
}
$builtVersion = Join-Path $workspaceRoot 'rust-java-rest/target/classes/com/reactor/rust/codegen/generator-version.properties'
if (Test-Path -LiteralPath $builtVersion) {
    $values = ConvertFrom-StringData (Get-Content -Raw -LiteralPath $builtVersion)
    Assert-Version 'Built ProjectGenerator' $values['framework.version'] $expectedVersion
}

if ($checked -eq 0) {
    Write-Error 'No sample project using rust-java-platform-parent was found.'
    exit 1
}

if ($mismatches.Count -gt 0) {
    $mismatches | ForEach-Object { Write-Host "[MISMATCH] $_" -ForegroundColor Red }
    exit 1
}

Write-Host "Version alignment verified: $checked sample project(s) use rust-java-platform-parent $expectedVersion."
exit 0
