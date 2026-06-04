param(
    [int] $Repeats = 3,
    [string[]] $JvmPresets = @("cpu1", "cpu1-xss160", "cpu1-xss128", "cpu1-nojit", "cpu1-nojit-xss128"),
    [int] $HostPortBase = 19020,
    [string] $ResultsDir = "",
    [string] $ContainerMemory = "96m",
    [ValidateSet("core-runtime", "classes")]
    [string] $FrameworkArtifactMode = "core-runtime",
    [ValidateSet("classes", "full-jar", "native-static")]
    [string] $DubboArtifactMode = "native-static",
    [int] $IdleSeconds = 30,
    [switch] $IncludeMicroRest
)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$MicroScript = Join-Path $ScriptDir "micro_runtime_rss_matrix.ps1"
$InvariantCulture = [System.Globalization.CultureInfo]::InvariantCulture

if ([string]::IsNullOrWhiteSpace($ResultsDir)) {
    $ResultsDir = Join-Path $ScriptDir ("results\jvm_baseline_rss_{0}" -f (Get-Date -Format "yyyyMMdd_HHmmss"))
}
$ResultsDir = [System.IO.Path]::GetFullPath($ResultsDir)
New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null

$AllowedJvmPresets = @(
    "current",
    "cpu1",
    "cpu1-xss192",
    "cpu1-xss160",
    "cpu1-xss128",
    "cpu1-nojit",
    "cpu1-nojit-xss160",
    "cpu1-nojit-xss128"
)

$JvmPresets = @(
    $JvmPresets |
        ForEach-Object { "$_" -split "," } |
        ForEach-Object { $_.Trim() } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
)

if ($JvmPresets.Count -eq 0) {
    throw "At least one JVM preset is required."
}

foreach ($preset in $JvmPresets) {
    if ($AllowedJvmPresets -notcontains $preset) {
        throw "Unsupported JVM preset '$preset'. Allowed: $($AllowedJvmPresets -join ', ')"
    }
}

function Invoke-Checked {
    param(
        [string] $FilePath,
        [string[]] $Arguments,
        [string] $WorkingDirectory
    )
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        Push-Location $WorkingDirectory
        $output = & $FilePath @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        Pop-Location
        $ErrorActionPreference = $previousErrorAction
    }
    $output | ForEach-Object { Write-Host $_ }
    if ($exitCode -ne 0) {
        throw "Command failed: $FilePath $($Arguments -join ' ')`n$($output -join "`n")"
    }
}

function To-Double {
    param($Value)
    if ($null -eq $Value -or [string]::IsNullOrWhiteSpace("$Value")) {
        return $null
    }
    return [double]::Parse(("$Value").Trim().Replace(",", "."), $InvariantCulture)
}

function Format-Number {
    param($Value)
    $number = To-Double $Value
    if ($null -eq $number) {
        return ""
    }
    return $number.ToString("0.##", $InvariantCulture)
}

function Average-Number {
    param($Values)
    $numbers = @($Values | ForEach-Object { To-Double $_ } | Where-Object { $null -ne $_ })
    if ($numbers.Count -eq 0) {
        return $null
    }
    return [math]::Round(($numbers | Measure-Object -Average).Average, 2)
}

function Min-Number {
    param($Values)
    $numbers = @($Values | ForEach-Object { To-Double $_ } | Where-Object { $null -ne $_ })
    if ($numbers.Count -eq 0) {
        return $null
    }
    return [math]::Round(($numbers | Measure-Object -Minimum).Minimum, 2)
}

function Max-Number {
    param($Values)
    $numbers = @($Values | ForEach-Object { To-Double $_ } | Where-Object { $null -ne $_ })
    if ($numbers.Count -eq 0) {
        return $null
    }
    return [math]::Round(($numbers | Measure-Object -Maximum).Maximum, 2)
}

$Rows = New-Object System.Collections.Generic.List[object]
$buildDone = $false

for ($repeat = 1; $repeat -le $Repeats; $repeat++) {
    $orderedPresets = if (($repeat % 2) -eq 0) {
        $copy = [string[]] $JvmPresets.Clone()
        [array]::Reverse($copy)
        $copy
    } else {
        $JvmPresets
    }

    $presetIndex = 0
    foreach ($preset in $orderedPresets) {
        $presetIndex++
        $runDir = Join-Path $ResultsDir ("run{0}_{1}" -f $repeat, $preset)
        $port = $HostPortBase + (($repeat - 1) * 100) + ($presetIndex * 2)
        $arguments = @(
            "-ExecutionPolicy", "Bypass",
            "-File", $MicroScript,
            "-SkipZookeeper",
            "-DubboArtifactMode", $DubboArtifactMode,
            "-HostPortBase", "$port",
            "-ContainerMemory", $ContainerMemory,
            "-FrameworkArtifactMode", $FrameworkArtifactMode,
            "-JvmPreset", $preset,
            "-IdleSeconds", "$IdleSeconds",
            "-ResultsDir", $runDir
        )
        if (-not $IncludeMicroRest) {
            $arguments += "-OnlyDubbo"
        }
        if ($buildDone) {
            $arguments += "-SkipBuild"
        }

        Invoke-Checked -FilePath "powershell" -Arguments $arguments -WorkingDirectory $ScriptDir
        $buildDone = $true

        $csv = Join-Path $runDir "micro_runtime_rss_matrix.csv"
        if (-not (Test-Path $csv)) {
            throw "Missing run CSV: $csv"
        }

        Import-Csv -Path $csv | ForEach-Object {
            $Rows.Add([PSCustomObject]@{
                repeat = $repeat
                jvm_preset = $preset
                scenario = $_.scenario
                phase = $_.phase
                smaps_rss_mib = $_.smaps_rss_mib
                smaps_pss_mib = $_.smaps_pss_mib
                private_dirty_mib = $_.private_dirty_mib
                docker_mem_mib = $_.docker_mem_mib
                threads = $_.threads
                source_csv = $csv
            })
        }
    }
}

$combinedCsv = Join-Path $ResultsDir "combined.csv"
$Rows | Export-Csv -NoTypeInformation -Path $combinedCsv -Encoding UTF8

$summaryRows = $Rows |
    Group-Object jvm_preset, scenario, phase |
    ForEach-Object {
        $first = $_.Group | Select-Object -First 1
        [PSCustomObject]@{
            jvm_preset = $first.jvm_preset
            scenario = $first.scenario
            phase = $first.phase
            count = $_.Count
            rss_avg = Average-Number ($_.Group | Select-Object -ExpandProperty smaps_rss_mib)
            rss_min = Min-Number ($_.Group | Select-Object -ExpandProperty smaps_rss_mib)
            rss_max = Max-Number ($_.Group | Select-Object -ExpandProperty smaps_rss_mib)
            pss_avg = Average-Number ($_.Group | Select-Object -ExpandProperty smaps_pss_mib)
            private_dirty_avg = Average-Number ($_.Group | Select-Object -ExpandProperty private_dirty_mib)
            docker_avg = Average-Number ($_.Group | Select-Object -ExpandProperty docker_mem_mib)
            threads_max = Max-Number ($_.Group | Select-Object -ExpandProperty threads)
        }
    } |
    Sort-Object scenario, phase, rss_avg

$summaryCsv = Join-Path $ResultsDir "summary.csv"
$summaryRows | Export-Csv -NoTypeInformation -Path $summaryCsv -Encoding UTF8

$summary = Join-Path $ResultsDir "summary.md"
$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("# JVM Baseline RSS Matrix")
$lines.Add("")
$lines.Add("- Date: $(Get-Date -Format o)")
$lines.Add("- Repeats: $Repeats")
$lines.Add("- JVM presets: $($JvmPresets -join ', ')")
$lines.Add("- Container memory limit: $ContainerMemory")
$lines.Add("- Framework artifact mode: $FrameworkArtifactMode")
$lines.Add("- Dubbo artifact mode: $DubboArtifactMode")
$lines.Add("- Idle seconds: $IdleSeconds")
$lines.Add("- Include micro-rest: $IncludeMicroRest")
$lines.Add("- Combined CSV: $combinedCsv")
$lines.Add("- Summary CSV: $summaryCsv")
$lines.Add("")
if ($Repeats -lt 3) {
    $lines.Add("> Note: repeats < 3 is a smoke check, not a release-grade benchmark.")
    $lines.Add("")
}
$lines.Add("## Summary")
$lines.Add("")
$lines.Add("| JVM Preset | Scenario | Phase | Count | RSS Avg MiB | RSS Min | RSS Max | PSS Avg MiB | Private Dirty Avg | Docker Avg MiB | Threads Max |")
$lines.Add("|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|")
foreach ($row in $summaryRows) {
    $lines.Add("| $($row.jvm_preset) | $($row.scenario) | $($row.phase) | $($row.count) | $(Format-Number $row.rss_avg) | $(Format-Number $row.rss_min) | $(Format-Number $row.rss_max) | $(Format-Number $row.pss_avg) | $(Format-Number $row.private_dirty_avg) | $(Format-Number $row.docker_avg) | $(Format-Number $row.threads_max) |")
}
$lines.Add("")
$lines.Add("## Per Run")
$lines.Add("")
$lines.Add("| Repeat | JVM Preset | Scenario | Phase | RSS MiB | PSS MiB | Private Dirty MiB | Docker Mem MiB | Threads |")
$lines.Add("|---:|---|---|---|---:|---:|---:|---:|---:|")
foreach ($row in ($Rows | Sort-Object repeat, jvm_preset, scenario, phase)) {
    $lines.Add("| $($row.repeat) | $($row.jvm_preset) | $($row.scenario) | $($row.phase) | $(Format-Number $row.smaps_rss_mib) | $(Format-Number $row.smaps_pss_mib) | $(Format-Number $row.private_dirty_mib) | $(Format-Number $row.docker_mem_mib) | $(Format-Number $row.threads) |")
}
$lines | Set-Content -Path $summary -Encoding UTF8

Write-Host "JVM baseline RSS matrix complete: $summary"
