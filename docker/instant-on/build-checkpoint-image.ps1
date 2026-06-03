param(
    [string]$BaseImage = "rust-java-rest-instanton-base:local",
    [string]$RestoreImage = "rust-java-rest-instanton:local",
    [string]$StartupIndexPackages = "com.reactor.rust.example",
    [int]$CheckpointTimeoutSeconds = 60,
    [switch]$SkipCriuCheck
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = (Resolve-Path (Join-Path $scriptDir "..\..")).Path
$containerName = "rust-java-rest-instanton-checkpoint-$PID"

docker build `
  -f (Join-Path $projectRoot "docker\instant-on\Dockerfile") `
  --build-arg "STARTUP_INDEX_PACKAGES=$StartupIndexPackages" `
  -t $BaseImage `
  $projectRoot

try {
    docker rm -f $containerName *> $null

    if (-not $SkipCriuCheck) {
        $checkOutput = & docker run --rm --entrypoint sh --user 0 `
          --privileged `
          --security-opt seccomp=unconfined `
          $BaseImage `
          -lc 'timeout 15s /usr/local/sbin/criu check --all' 2>&1
        if ($LASTEXITCODE -ne 0) {
            $checkOutput | ForEach-Object { Write-Host $_ }
            $joined = ($checkOutput -join "`n")
            if ($joined -match "couldn't suspend seccomp|Dumping seccomp filters not supported") {
                throw "CRIU preflight failed because seccomp is still active. Use the Ubuntu WSL Docker daemon or a CRIU-capable Linux/UBI host."
            }
            Write-Host "[InstantOn] CRIU preflight reported non-fatal warnings; continuing to the real checkpoint attempt."
        }
    }

    docker run `
      -d `
      --init `
      --name $containerName `
      --privileged `
      --security-opt seccomp=unconfined `
      -e REACTOR_INSTANTON_CHECKPOINT_ENABLED=true `
      -e REACTOR_INSTANTON_CHECKPOINT_FAIL_ON_UNAVAILABLE=true `
      -e REACTOR_RUNTIME_PROFILE=fast-start `
      $BaseImage | Out-Null

    $deadline = (Get-Date).AddSeconds($CheckpointTimeoutSeconds)
    do {
        docker cp "${containerName}:/checkpoint/inventory.img" - *> $null
        if ($LASTEXITCODE -eq 0) {
            break
        }
        $running = docker inspect -f '{{.State.Running}}' $containerName
        if ($running -ne "true") {
            break
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)

    docker cp "${containerName}:/checkpoint/inventory.img" - *> $null
    if ($LASTEXITCODE -ne 0) {
        docker logs $containerName
        throw "Checkpoint data was not produced within $CheckpointTimeoutSeconds seconds"
    }

    docker commit `
      --change 'ENV REACTOR_INSTANTON_RESTORE=true' `
      $containerName `
      $RestoreImage | Out-Null

    Write-Host "[InstantOn] restore image created: $RestoreImage"
    Write-Host "[InstantOn] run with:"
    Write-Host "docker run --rm --privileged --security-opt seccomp=unconfined -p 8080:8080 $RestoreImage"
} finally {
    docker rm -f $containerName *> $null
}
