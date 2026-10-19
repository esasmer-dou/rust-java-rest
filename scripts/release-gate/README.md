# Frozen Release Comparison

This maintainer workflow removes local Docker contention from a pending A/B
comparison. It does not change an application's runtime, profiles or native ABI.

`Frozen Release Comparison` starts cache, writer and consumer on three independent
`ubuntu-24.04` GitHub-hosted runners. Each lane compares both sides on its own
runner with the same image/JVM and resource budgets. The existing workload,
three fresh-start pairs, semantic body checks and raw error evidence are retained.
Parallel lanes do not share a Docker daemon or application CPU quota.

The input is a temporary **draft** release containing `contract.json`,
`harness.tar.gz` and `images.tar.gz`. Pin the contract's SHA-256 at dispatch.
The contract identifies every image by its immutable ID and every archive by
SHA-256. Do not put production credentials or data in this bundle. The only
credentials used by these fixtures are disposable local test credentials.

The candidate uses the normal allocator, normal Java entrypoint and no additional
JVM tuning. Rejected allocator, huge-page or nursery experiments are excluded.
The workflow retains raw logs, sampled RSS, idle memory, errors and both successful
and failed verdicts. Each lane is bounded to 30 minutes; infrastructure failures
are not converted to a performance PASS.

```powershell
./scripts/release-gate/Test-Comparison.ps1
gh workflow run release-performance.yml `
  -f snapshot_tag=gate-snapshot-YYYYMMDD-HHMMSS `
  -f contract_sha256=<SHA256-of-contract.json>
```

`Assert-Comparison.ps1` fails on missing/incomplete evidence or a failed strict
RPS, p99, correctness or mean/worst peak RSS check. Writer additionally requires
unchanged equal work and no measured CPU regression. No thresholds are loosened
after seeing the result.

This is **qualification evidence, not a product release**. It does not replace
clean native provenance, ABI checks, artifact/version alignment, the agent's
separate Production Gate or the existing package/release workflows. A snapshot
from uncommitted sources must not be called a clean release build. Release only
the reviewed, correctly versioned source and verified matching native artifacts.
Delete the draft bundle after evidence is downloaded; never publish it as an
end-user release or attach these test images to a stable release.
