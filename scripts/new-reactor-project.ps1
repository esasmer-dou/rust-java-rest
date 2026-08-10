[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('rest', 'cache-reader', 'cache-writer', 'dubbo-static', 'dubbo-zookeeper')]
    [string]$Mode,

    [Parameter(Mandatory = $true)]
    [string]$Artifact,

    [Parameter(Mandatory = $true)]
    [string]$Output,

    [string]$Group = 'com.example',
    [string]$Package,
    [int]$Port = 8080,
    [string]$Version = '4.2.0'
)

$ErrorActionPreference = 'Stop'
$checkoutJar = Join-Path (Split-Path -Parent $PSScriptRoot) "target/rust-java-rest-$Version-codegen.jar"
$jar = $checkoutJar
if (-not (Test-Path -LiteralPath $jar)) {
    $localRepository = & mvn -q help:evaluate '-Dexpression=settings.localRepository' '-DforceStdout'
    if ($LASTEXITCODE -ne 0 -or @($localRepository).Count -ne 1 -or [string]::IsNullOrWhiteSpace($localRepository)) {
        throw 'Could not determine the Maven local repository from settings.xml.'
    }
    $repositoryJar = Join-Path $localRepository.Trim() "com/reactor/rust-java-rest/$Version/rust-java-rest-$Version-codegen.jar"
    if (-not (Test-Path -LiteralPath $repositoryJar)) {
        Push-Location ([System.IO.Path]::GetTempPath())
        try {
            & mvn -q dependency:get "-Dartifact=com.reactor:rust-java-rest:${Version}:jar:codegen"
            if ($LASTEXITCODE -ne 0) {
                throw 'Could not resolve the rust-java-rest codegen artifact. Check Maven settings.xml.'
            }
        } finally {
            Pop-Location
        }
    }
    if (-not (Test-Path -LiteralPath $repositoryJar)) {
        throw "Maven completed without installing $repositoryJar. Run 'mvn install' in rust-java-rest or publish the codegen classifier."
    }
    $jar = $repositoryJar
}

$arguments = @(
    '-cp', $jar,
    'com.reactor.rust.codegen.ProjectGenerator',
    '--mode', $Mode,
    '--artifact', $Artifact,
    '--output', $Output,
    '--group', $Group,
    '--port', $Port
)
if ($Package) {
    $arguments += @('--package', $Package)
}

& java @arguments
if ($LASTEXITCODE -ne 0) {
    throw "Project generation failed with exit code $LASTEXITCODE"
}
