#!/usr/bin/env sh
set -eu

APP_MAIN="${APP_MAIN:-com.reactor.rust.example.ReactorRustHyperApplication}"
APP_CP="${APP_CP:-/app/classes:/app/dependency/*}"
CHECKPOINT_DIR="${REACTOR_INSTANTON_CHECKPOINT_DIR:-/checkpoint}"
RESTORE_LOG="${REACTOR_INSTANTON_RESTORE_LOG:-/tmp/reactor-criu-restore.log}"
JAVA_OPTS="${JAVA_OPTS:-}"
REACTOR_PROFILE="${REACTOR_RUNTIME_PROFILE:-fast-start}"
CHECKPOINT_FAIL_ON_UNAVAILABLE="${REACTOR_INSTANTON_CHECKPOINT_FAIL_ON_UNAVAILABLE:-true}"

if [ "${REACTOR_INSTANTON_RESTORE:-false}" = "true" ]; then
  if [ ! -d "${CHECKPOINT_DIR}" ]; then
    echo "[InstantOn] checkpoint directory does not exist: ${CHECKPOINT_DIR}" >&2
    exit 70
  fi
  if [ ! -f "${CHECKPOINT_DIR}/inventory.img" ]; then
    echo "[InstantOn] checkpoint inventory is missing under ${CHECKPOINT_DIR}" >&2
    exit 70
  fi
  echo "[InstantOn] restoring JVM from ${CHECKPOINT_DIR}"
  exec /usr/local/sbin/criu restore \
    -D "${CHECKPOINT_DIR}" \
    --shell-job \
    --tcp-close \
    --file-locks \
    -v4 \
    --log-file="${RESTORE_LOG}"
fi

CRIU_OPTS=""
REACTOR_PROPS="-Dreactor.runtime.profile=${REACTOR_PROFILE}"
if [ "${REACTOR_INSTANTON_CHECKPOINT_ENABLED:-false}" = "true" ]; then
  mkdir -p "${CHECKPOINT_DIR}"
  CRIU_OPTS="--add-modules openj9.criu -XX:+EnableCRIUSupport"
  REACTOR_PROPS="${REACTOR_PROPS} -Dreactor.instanton.checkpoint.enabled=true"
  REACTOR_PROPS="${REACTOR_PROPS} -Dreactor.instanton.checkpoint.fail-on-unavailable=${CHECKPOINT_FAIL_ON_UNAVAILABLE}"
  echo "[InstantOn] checkpoint mode enabled; image dir=${CHECKPOINT_DIR}"
fi

exec java ${JAVA_OPTS} ${CRIU_OPTS} ${REACTOR_PROPS} \
  -Dserver.port="${SERVER_PORT:-8080}" \
  -Dreactor.instanton.checkpoint.dir="${CHECKPOINT_DIR}" \
  -cp "${APP_CP}" \
  "${APP_MAIN}"
