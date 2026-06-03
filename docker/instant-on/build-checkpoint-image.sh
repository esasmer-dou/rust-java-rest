#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

BASE_IMAGE="${BASE_IMAGE:-rust-java-rest-instanton-base:local}"
RESTORE_IMAGE="${RESTORE_IMAGE:-rust-java-rest-instanton:local}"
CONTAINER_NAME="${CONTAINER_NAME:-rust-java-rest-instanton-checkpoint-$$}"
STARTUP_INDEX_PACKAGES="${STARTUP_INDEX_PACKAGES:-com.reactor.rust.example}"
CHECKPOINT_TIMEOUT_SECONDS="${CHECKPOINT_TIMEOUT_SECONDS:-60}"
SKIP_CRIU_CHECK="${SKIP_CRIU_CHECK:-false}"

docker build \
  -f "${PROJECT_ROOT}/docker/instant-on/Dockerfile" \
  --build-arg STARTUP_INDEX_PACKAGES="${STARTUP_INDEX_PACKAGES}" \
  -t "${BASE_IMAGE}" \
  "${PROJECT_ROOT}"

if [ "${SKIP_CRIU_CHECK}" != "true" ]; then
  set +e
  CRIU_CHECK_OUTPUT="$(docker run --rm --entrypoint sh --user 0 \
    --privileged \
    --security-opt seccomp=unconfined \
    "${BASE_IMAGE}" \
    -lc 'timeout 15s /usr/local/sbin/criu check --all' 2>&1)"
  CRIU_CHECK_RC=$?
  set -e
  if [ "${CRIU_CHECK_RC}" -ne 0 ]; then
    echo "${CRIU_CHECK_OUTPUT}" >&2
    if printf '%s' "${CRIU_CHECK_OUTPUT}" | grep -Eq "couldn't suspend seccomp|Dumping seccomp filters not supported"; then
      echo "[InstantOn] CRIU preflight failed because seccomp is still active. Use the Ubuntu WSL Docker daemon or a CRIU-capable Linux/UBI host." >&2
      exit 72
    fi
    echo "[InstantOn] CRIU preflight reported non-fatal warnings; continuing to the real checkpoint attempt." >&2
  fi
fi

cleanup() {
  docker rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker run \
  -d \
  --init \
  --name "${CONTAINER_NAME}" \
  --privileged \
  --security-opt seccomp=unconfined \
  -e REACTOR_INSTANTON_CHECKPOINT_ENABLED=true \
  -e REACTOR_INSTANTON_CHECKPOINT_FAIL_ON_UNAVAILABLE=true \
  -e REACTOR_RUNTIME_PROFILE=fast-start \
  "${BASE_IMAGE}" >/dev/null

deadline=$((SECONDS + CHECKPOINT_TIMEOUT_SECONDS))
while [ "${SECONDS}" -lt "${deadline}" ]; do
  if docker cp "${CONTAINER_NAME}:/checkpoint/inventory.img" - >/dev/null 2>&1; then
    break
  fi
  running="$(docker inspect -f '{{.State.Running}}' "${CONTAINER_NAME}" 2>/dev/null || echo false)"
  if [ "${running}" != "true" ]; then
    break
  fi
  sleep 1
done

if ! docker cp "${CONTAINER_NAME}:/checkpoint/inventory.img" - >/dev/null 2>&1; then
  echo "[InstantOn] checkpoint data was not produced" >&2
  docker logs "${CONTAINER_NAME}" >&2 || true
  exit 71
fi

docker commit \
  --change 'ENV REACTOR_INSTANTON_RESTORE=true' \
  "${CONTAINER_NAME}" \
  "${RESTORE_IMAGE}" >/dev/null

echo "[InstantOn] restore image created: ${RESTORE_IMAGE}"
echo "[InstantOn] run with:"
echo "docker run --rm --privileged --security-opt seccomp=unconfined -p 8080:8080 ${RESTORE_IMAGE}"
