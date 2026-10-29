[CmdletBinding()]
param([string]$WorkspaceRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $WorkspaceRoot).Path
$verifier = Join-Path $PSScriptRoot 'verify-version-alignment.ps1'
$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$fixture = Join-Path $tempBase ('reactor-alignment-test-' + [Guid]::NewGuid())
$shell = (Get-Process -Id $PID).Path
$paths = @(
    'rust-java-rest/pom.xml', 'rust-java-rest/platform/parent/pom.xml',
    'rust-java-rest/platform/bom/pom.xml', 'java-rust-dubbo/pom.xml', 'java-rust-cache/pom.xml',
    'rust-sample-model/pom.xml', 'rest-sample-utility/pom.xml', 'rest-sample-cache-reader/pom.xml',
    'rest-sample-cache-writer/pom.xml', 'rest-sample-dubbo-consumer/pom.xml',
    'rest-sample-dubbo-provider/pom.xml',
    'rust-java-rest/src/main/java/com/reactor/rust/codegen/ProjectGenerator.java',
    'rust-java-rest/src/codegen/resources/com/reactor/rust/codegen/generator-version.properties'
)

function Initialize-Fixture {
    foreach ($relative in $paths) {
        $destination = Join-Path $fixture $relative
        New-Item -ItemType Directory -Force -Path (Split-Path $destination) | Out-Null
        Copy-Item -LiteralPath (Join-Path $root $relative) -Destination $destination -Force
    }
}

function Assert-Exit([int]$Expected, [string]$Label) {
    $output = & $shell -NoLogo -NoProfile -NonInteractive -File $verifier -WorkspaceRoot $fixture 2>&1
    if ($LASTEXITCODE -ne $Expected) { throw "$Label failed: $output" }
    Write-Host "PASS: $Label"
}

function Mutate-Version([string]$Path, [string]$NodePath) {
    $file = Join-Path $fixture $Path
    [xml]$document = Get-Content -Raw -LiteralPath $file
    $namespace = [Xml.XmlNamespaceManager]::new($document.NameTable)
    $namespace.AddNamespace('m', 'http://maven.apache.org/POM/4.0.0')
    $node = $document.SelectSingleNode($NodePath, $namespace)
    if ($null -eq $node) { throw "Fixture node not found: $NodePath" }
    $node.InnerText = '0.0.0-invalid'
    $document.Save($file)
}

try {
    Initialize-Fixture
    Assert-Exit 0 'aligned parent, BOM, processors, shared contracts and generator'
    Mutate-Version 'rust-sample-model/pom.xml' '/m:project/m:parent/m:version'
    Assert-Exit 1 'sample parent mismatch fails closed'
    Initialize-Fixture
    Mutate-Version 'rust-java-rest/platform/bom/pom.xml' '/m:project/m:properties/m:java-rust-dubbo.version'
    Assert-Exit 1 'BOM dependency mismatch fails closed'
    Initialize-Fixture
    Mutate-Version 'rest-sample-dubbo-provider/pom.xml' `
        '/m:project/m:dependencies/m:dependency[m:artifactId="java-rust-dubbo"]/m:version'
    Assert-Exit 1 'sample explicit dependency mismatch fails closed'
    Initialize-Fixture
    Mutate-Version 'java-rust-dubbo/pom.xml' '/m:project/m:properties/m:rust-java-rest.version'
    Assert-Exit 1 'library REST dependency mismatch fails closed'
    Initialize-Fixture
    $readerPom = Join-Path $fixture 'rest-sample-cache-reader/pom.xml'
    [xml]$reader = Get-Content -LiteralPath $readerPom -Raw
    $starter = @($reader.project.dependencies.dependency |
            Where-Object { [string]$_.artifactId -eq '${sample.starter.artifact}' })
    if ($starter.Count -ne 1) { throw 'Expected property-selected starter in the sample POM' }
    $version = $reader.CreateElement('version', $reader.DocumentElement.NamespaceURI)
    $version.InnerText = '4.7.0'
    $starter[0].AppendChild($version) | Out-Null
    $reader.Save($readerPom)
    Assert-Exit 1 'property-selected starter dependency mismatch fails closed'
    Initialize-Fixture
    $template = Join-Path $fixture $paths[-1]
    Set-Content -LiteralPath $template -Encoding utf8 -Value 'framework.version=0.0.0-invalid'
    Assert-Exit 1 'hardcoded generator resource fails closed'
} finally {
    $absolute = [IO.Path]::GetFullPath($fixture)
    if (-not $absolute.StartsWith($tempBase, [StringComparison]::OrdinalIgnoreCase) `
            -or (Split-Path $absolute -Leaf) -notlike 'reactor-alignment-test-*') {
        throw "Unsafe temporary cleanup target: $absolute"
    }
    if (Test-Path -LiteralPath $absolute) { Remove-Item -LiteralPath $absolute -Recurse -Force }
}

# Expected failing child processes must not leak their exit code to an invoking gate.
exit 0
