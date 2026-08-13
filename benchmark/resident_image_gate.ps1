param(
    [Parameter(Mandatory = $true)]
    [string] $BaselineImage,

    [Parameter(Mandatory = $true)]
    [string] $CandidateImage,

    [string] $ResultsDir = "",
    [int] $RepeatCount = 4,
    [int] $Concurrency = 256,
    [string] $Duration = "10s",
    [string] $PreWarmDuration = "10s",
    [string] $EndpointClasses = "annotated-generated-json,echo-parse,small-json-direct",
    [int] $InterPairCooldownSeconds = 5,
    [int] $Threads = 4,
    [double] $CpuLimit = 1.0,
    [string] $MemoryLimit = "128m",
    [string] $BaselineCpuSet = "",
    [string] $CandidateCpuSet = "",
    [string] $RunnerCpuSet = "",
    [double] $RunnerCpuLimit = 2.0,
    [string] $RunnerImage = "reactor-benchmark-runner:local",
    [string] $BaselineJavaOptsAppend = "",
    [string] $CandidateJavaOptsAppend = "",
    [string] $AdditionalNetwork = "",
    [double] $MaxMemoryRegressionMiB = 1.0
)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false

if ($RepeatCount -lt 3) {
    throw "RepeatCount must be at least 3 for a performance gate."
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($ResultsDir)) {
    $ResultsDir = Join-Path $ScriptDir ("results\resident_" + (Get-Date -Format "yyyyMMdd_HHmmss"))
}
$ResultsDir = [System.IO.Path]::GetFullPath($ResultsDir)
$baselineDir = Join-Path $ResultsDir "baseline"
$candidateDir = Join-Path $ResultsDir "candidate"
$comparisonDir = Join-Path $ResultsDir "comparison"
New-Item -ItemType Directory -Force $baselineDir, $candidateDir, $comparisonDir | Out-Null

$suffix = [Guid]::NewGuid().ToString("N").Substring(0, 8)
$network = "reactor-resident-$suffix"
$baselineContainer = "reactor-resident-baseline-$suffix"
$candidateContainer = "reactor-resident-candidate-$suffix"
$runnerContainer = "reactor-resident-runner-$suffix"
$javaOpts = @(
    "-Xms8m",
    "-Xmx40m",
    "-Xss256k",
    "-Xquickstart",
    "-Xtune:virtualized",
    "-Xshareclasses:none",
    "-XX:ActiveProcessorCount=1",
    "-Xgc:threads=1",
    "-XX:-TransparentHugePage",
    "-Dreactor.runtime.profile=micro-rest",
    "-Dreactor.rust.log.level=error",
    "-Dreactor.rust.java.log.level=warn",
    "-Dfile.encoding=UTF-8",
    "-Djava.security.egd=file:/dev/./urandom"
) -join " "

$payload = '{"orderId":"ORD-1001","amount":350.75,"paid":true,"address":{"city":"Ankara","street":"Ataturk Cd."},"customer":{"name":"mustafa customer a.s","email":"mustafa@gmai.com"},"items":[{"name":"test0","price":12.89},{"name":"test1","price":13.89},{"name":"test2","price":14.89}]}'
[System.IO.File]::WriteAllText(
    (Join-Path $ResultsDir "post_body.json"),
    $payload,
    [System.Text.UTF8Encoding]::new($false))

$endpoints = @(
    [PSCustomObject]@{
        Name = "users_search_generated"
        Class = "annotated-generated-json"
        Method = "GET"
        Path = "/users/search?name=load&page=1"
    },
    [PSCustomObject]@{
        Name = "echo_parse_business"
        Class = "echo-parse"
        Method = "POST"
        Path = "/api/v1/echo"
    },
    [PSCustomObject]@{
        Name = "candidates_direct_bodyless"
        Class = "small-json-direct"
        Method = "GET"
        Path = "/api/v1/candidates/direct"
    },
    [PSCustomObject]@{
        Name = "heavy100_dynamic_producer"
        Class = "dynamic-producer-json"
        Method = "GET"
        Path = "/api/v1/heavy/dto?items=100"
    },
    [PSCustomObject]@{
        Name = "heavy100_direct_writer"
        Class = "direct-json-writer"
        Method = "GET"
        Path = "/api/v1/heavy?items=100"
    },
    [PSCustomObject]@{
        Name = "heavy100_producer_json"
        Class = "producer-json"
        Method = "GET"
        Path = "/api/v1/heavy/producer?items=100"
    },
    [PSCustomObject]@{
        Name = "heavy100_raw"
        Class = "raw-json"
        Method = "GET"
        Path = "/api/v1/heavy/raw"
    }
)

$selectedEndpointClasses = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase)
foreach ($endpointClass in ($EndpointClasses -split ",")) {
    $trimmed = $endpointClass.Trim()
    if (-not [string]::IsNullOrWhiteSpace($trimmed)) {
        [void] $selectedEndpointClasses.Add($trimmed)
    }
}
$endpoints = @($endpoints | Where-Object { $selectedEndpointClasses.Contains($_.Class) })
if ($endpoints.Count -eq 0) {
    throw "EndpointClasses did not select a resident benchmark endpoint: $EndpointClasses"
}

function Invoke-DockerChecked {
    param([string[]] $Arguments)
    $output = & docker @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "docker $($Arguments -join ' ') failed:`n$($output -join "`n")"
    }
    return $output
}

function Start-FrameworkContainer {
    param(
        [string] $Name,
        [string] $Image,
        [string] $CpuSet,
        [string] $JavaOptsAppend
    )
    $effectiveJavaOpts = @($javaOpts, $JavaOptsAppend) |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    $args = @(
        "run", "-d", "--name", $Name,
        "--network", $network,
        "--cpus", "$CpuLimit",
        "--memory", $MemoryLimit,
        "-e", "JAVA_TOOL_OPTIONS=",
        "-e", "JAVA_AGENT_OPTS=",
        "-e", "JAVA_OPTS=$($effectiveJavaOpts -join ' ')"
    )
    if (-not [string]::IsNullOrWhiteSpace($CpuSet)) {
        $args += @("--cpuset-cpus", $CpuSet)
    }
    $args += $Image
    Invoke-DockerChecked -Arguments $args | Out-Null
    if (-not [string]::IsNullOrWhiteSpace($AdditionalNetwork)) {
        Invoke-DockerChecked -Arguments @("network", "connect", $AdditionalNetwork, $Name) | Out-Null
    }
}

function Start-RunnerContainer {
    $args = @(
        "run", "-d", "--name", $runnerContainer,
        "--network", $network,
        "--cpus", "$RunnerCpuLimit",
        "-v", "$ResultsDir`:/results",
        "--entrypoint", "sh"
    )
    if (-not [string]::IsNullOrWhiteSpace($RunnerCpuSet)) {
        $args += @("--cpuset-cpus", $RunnerCpuSet)
    }
    $args += @($RunnerImage, "-c", "sleep 86400")
    Invoke-DockerChecked -Arguments $args | Out-Null
}

function Wait-FrameworkReady {
    param([string] $Name)
    $url = "http://${Name}:8080/api/v1/candidates"
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        $args = @(
            "exec", $runnerContainer,
            "curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", $url)
        $status = & docker @args 2>$null
        if ($LASTEXITCODE -eq 0 -and "$status" -match "^2[0-9]{2}$") {
            return
        }
        Start-Sleep -Milliseconds 250
    }
    $logs = & docker logs $Name 2>&1
    throw "$Name did not become HTTP-ready.`n$($logs -join "`n")"
}

function Invoke-Probe {
    param(
        [string] $Container,
        [object] $Endpoint,
        [string] $ProbeDuration,
        [switch] $DiscardOutput
    )
    $url = "http://${Container}:8080$($Endpoint.Path)"
    $args = @(
        "exec", $runnerContainer,
        "load-probe",
        "--url", $url,
        "--method", $Endpoint.Method,
        "--concurrency", "$Concurrency",
        "--duration", $ProbeDuration,
        "--timeout-ms", "10000"
    )
    if ($Endpoint.Method -eq "POST") {
        $args += @("--body-file", "/results/post_body.json")
    }
    $output = & docker @args 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "load-probe failed for $Container/$($Endpoint.Name):`n$($output -join "`n")"
    }
    if ($DiscardOutput) {
        return
    }
    return ($output -join "`n")
}

function Get-ContainerMemoryMiB {
    param([string] $Container)
    $usage = & docker stats $Container --no-stream --format '{{.MemUsage}}' 2>$null
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace("$usage")) {
        return ""
    }
    $value = ("$usage" -split "/")[0].Trim()
    if ($value -match "^([0-9.]+)(KiB|MiB|GiB)$") {
        $number = [double]::Parse($Matches[1], [System.Globalization.CultureInfo]::InvariantCulture)
        $mib = switch ($Matches[2]) {
            "KiB" { $number / 1024.0 }
            "GiB" { $number * 1024.0 }
            default { $number }
        }
        return [math]::Round($mib, 2)
    }
    return ""
}

function Get-ProcessRssMiB {
    param([string] $Container)
    $awkCommand = 'awk ''/^VmRSS:/ { print $2 }'' /proc/1/status'
    $rss = & docker exec $Container sh -c $awkCommand 2>$null
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace("$rss")) {
        return ""
    }
    return [math]::Round(([double] "$rss") / 1024.0, 2)
}

function Parse-ProbeOutput {
    param([string] $Text)
    $rps = if ($Text -match "Requests/sec:\s+([0-9.]+)") { $Matches[1] } else { "N/A" }
    $avg = if ($Text -match "(?m)^\s*Latency\s+([0-9.]+[a-zA-Zµ]+)") { $Matches[1] } else { "N/A" }
    $p50 = if ($Text -match "(?m)^\s*50%\s+([0-9.]+[a-zA-Zµ]+)") { $Matches[1] } else { "N/A" }
    $p90 = if ($Text -match "(?m)^\s*90%\s+([0-9.]+[a-zA-Zµ]+)") { $Matches[1] } else { "N/A" }
    $p99 = if ($Text -match "(?m)^\s*99%\s+([0-9.]+[a-zA-Zµ]+)") { $Matches[1] } else { "N/A" }
    $errors = if ($Text -match "(?mi)^\s*errors total:\s+([0-9]+)") { $Matches[1] } else { "" }
    $statuses = [regex]::Matches($Text, "(?m)^Status\s+([0-9]{3}):\s+([0-9]+)") |
            ForEach-Object { "$($_.Groups[1].Value)=$($_.Groups[2].Value)" }
    return [PSCustomObject]@{
        Rps = $rps
        Avg = $avg
        P50 = $p50
        P90 = $p90
        P99 = $p99
        Errors = $errors
        HttpStatus = $statuses -join ", "
    }
}

$baselineRows = [System.Collections.Generic.List[object]]::new()
$candidateRows = [System.Collections.Generic.List[object]]::new()

try {
    Invoke-DockerChecked -Arguments @("image", "inspect", $BaselineImage) | Out-Null
    Invoke-DockerChecked -Arguments @("image", "inspect", $CandidateImage) | Out-Null
    Invoke-DockerChecked -Arguments @("image", "inspect", $RunnerImage) | Out-Null
    Invoke-DockerChecked -Arguments @("network", "create", $network) | Out-Null
    Start-RunnerContainer
    Start-FrameworkContainer -Name $baselineContainer -Image $BaselineImage `
            -CpuSet $BaselineCpuSet -JavaOptsAppend $BaselineJavaOptsAppend
    Start-FrameworkContainer -Name $candidateContainer -Image $CandidateImage `
            -CpuSet $CandidateCpuSet -JavaOptsAppend $CandidateJavaOptsAppend
    Wait-FrameworkReady -Name $baselineContainer
    Wait-FrameworkReady -Name $candidateContainer

    foreach ($endpoint in $endpoints) {
        Invoke-Probe -Container $baselineContainer -Endpoint $endpoint `
                -ProbeDuration $PreWarmDuration -DiscardOutput
        Invoke-Probe -Container $candidateContainer -Endpoint $endpoint `
                -ProbeDuration $PreWarmDuration -DiscardOutput
    }

    for ($run = 1; $run -le $RepeatCount; $run++) {
        $targetOrder = if (($run % 2) -eq 1) {
            @("baseline", "candidate")
        } else {
            @("candidate", "baseline")
        }
        $endpointOrder = @($endpoints)
        if (($run % 2) -eq 0) {
            [array]::Reverse($endpointOrder)
        }

        foreach ($endpoint in $endpointOrder) {
            foreach ($target in $targetOrder) {
                $container = if ($target -eq "baseline") { $baselineContainer } else { $candidateContainer }
                $raw = Invoke-Probe -Container $container -Endpoint $endpoint -ProbeDuration $Duration
                $parsed = Parse-ProbeOutput -Text $raw
                $targetDir = if ($target -eq "baseline") { $baselineDir } else { $candidateDir }
                $rawFile = Join-Path $targetDir ("$($endpoint.Name)_c${Concurrency}_r${run}.txt")
                $raw | Set-Content -LiteralPath $rawFile -Encoding utf8
                $rss = Get-ProcessRssMiB -Container $container
                $memory = Get-ContainerMemoryMiB -Container $container
                $row = [PSCustomObject]@{
                    Target = "rust_java"
                    Endpoint = $endpoint.Name
                    EndpointClass = $endpoint.Class
                    Method = $endpoint.Method
                    Concurrency = $Concurrency
                    Run = $run
                    Rps = $parsed.Rps
                    Avg = $parsed.Avg
                    P50 = $parsed.P50
                    P90 = $parsed.P90
                    P99 = $parsed.P99
                    Errors = $parsed.Errors
                    HttpStatus = $parsed.HttpStatus
                    RssBeforeMiB = $rss
                    RssAfterMiB = $rss
                    MemBeforeMiB = $memory
                    MemAfterMiB = $memory
                    MaxContainerMemMiB = $memory
                    RawFile = $rawFile
                    MemoryFile = ""
                    MetricsFile = ""
                    StartupReadyMs = ""
                    StartupReachableMs = ""
                }
                if ($target -eq "baseline") {
                    $baselineRows.Add($row)
                } else {
                    $candidateRows.Add($row)
                }
            }
            if ($InterPairCooldownSeconds -gt 0) {
                Start-Sleep -Seconds $InterPairCooldownSeconds
            }
        }
    }

    $baselineRows | Export-Csv -LiteralPath (Join-Path $baselineDir "results.csv") `
            -NoTypeInformation -Encoding utf8
    $candidateRows | Export-Csv -LiteralPath (Join-Path $candidateDir "results.csv") `
            -NoTypeInformation -Encoding utf8

    $metadata = [ordered]@{
        baseline_image = $BaselineImage
        candidate_image = $CandidateImage
        execution_model = "resident containers; alternating target and endpoint order"
        repeats = $RepeatCount
        concurrency = $Concurrency
        duration = $Duration
        pre_warm_duration = $PreWarmDuration
        endpoint_classes = @($endpoints | ForEach-Object { $_.Class })
        inter_pair_cooldown_seconds = $InterPairCooldownSeconds
        cpu_limit = $CpuLimit
        baseline_cpu_set = $BaselineCpuSet
        candidate_cpu_set = $CandidateCpuSet
        runner_cpu_set = $RunnerCpuSet
        runner_cpu_limit = $RunnerCpuLimit
        memory_limit = $MemoryLimit
        java_opts = $javaOpts
        baseline_java_opts_append = $BaselineJavaOptsAppend
        candidate_java_opts_append = $CandidateJavaOptsAppend
        additional_network = $AdditionalNetwork
        max_memory_regression_mib = $MaxMemoryRegressionMiB
    }
    $metadata | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $ResultsDir "metadata.json") -Encoding utf8

    & (Join-Path $ScriptDir "compare_framework_results.ps1") `
            -BaselineResultsDir $baselineDir `
            -CandidateResultsDir $candidateDir `
            -OutputDir $comparisonDir `
            -StrictConcurrencyLevels @($Concurrency) `
            -MinStrictRuns 3 `
            -MaxMemoryRegressionMiB $MaxMemoryRegressionMiB
    if ($LASTEXITCODE -ne 0) {
        throw "Framework comparison failed."
    }
} finally {
    if (& docker ps -a --format "{{.Names}}" | Where-Object { $_ -eq $baselineContainer }) {
        & docker logs $baselineContainer *> (Join-Path $ResultsDir "baseline-app.log")
    }
    if (& docker ps -a --format "{{.Names}}" | Where-Object { $_ -eq $candidateContainer }) {
        & docker logs $candidateContainer *> (Join-Path $ResultsDir "candidate-app.log")
    }
    & docker rm -f $baselineContainer $candidateContainer $runnerContainer *> $null
    & docker network rm $network *> $null
}

Write-Output "Resident image gate complete: $ResultsDir"
