# Frozen Release Comparison

This maintainer workflow removes local Docker contention from a pending A/B
comparison. It does not change an application's runtime, profiles or native ABI.

`Frozen Release Comparison` starts cache, writer and consumer on three independent
`ubuntu-24.04` GitHub-hosted runners. Each lane compares both sides on its own
runner with the same image/JVM and resource budgets. The existing workload,
three fresh-start pairs, semantic body checks and raw error evidence are retained.
Parallel lanes do not share a Docker daemon or application CPU quota.

A separate input-download job has `contents: write` only because GitHub restricts
draft visibility to users with push access. It verifies checksums and the clean
native build without executing downloaded test code. Test jobs have read-only
repository permissions and no persisted Git credentials. The download job has a
10-minute limit; each independent comparison lane has a 30-minute limit.

The input is a temporary **draft** release containing `contract.json`,
`harness.tar.gz`, `images.tar.gz` and a clean `librust_hyper.so`. Pin the contract's SHA-256 at dispatch.
The contract identifies every image by its immutable ID and every archive by
SHA-256. Do not put production credentials or data in this bundle. The only
credentials used by these fixtures are disposable local test credentials.

The candidate images receive the same checksum-verified SO from the completed
clean native build before warmup. Its full source revision and build run are
recorded in `RuntimeOverride`. Original image IDs and measured derived image IDs
are both retained; the embedded native artifact is checked in each candidate.
Capture `native-build-run.json` with the authorized maintainer CLI and include its
checksum in the contract. This avoids granting the test workflow a personal token
for the separate private native-source repository. The record must identify the
successful push build, full source revision and expected repository.

The candidate uses the normal allocator, normal Java entrypoint and no additional
JVM tuning. Rejected allocator, huge-page or nursery experiments are excluded.
The workflow retains raw logs, sampled RSS, idle memory, errors and both successful
and failed verdicts. Each lane is bounded to 30 minutes; infrastructure failures
are not converted to a performance PASS.

`lanes=writer` runs only a writer reconfirmation. It is not a three-lane gate PASS.
`WriterWarmRounds=80` in a newly pinned contract gives both sides 400 warmup
publications, while measured work remains 200 publications per JVM. Keep the
original failed run. Do not subtract compilation time or loosen CPU/p99/RSS
thresholds. Reuse other lane evidence only when image, source and native identities
are unchanged, recording the distinct contracts and run IDs explicitly.

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
