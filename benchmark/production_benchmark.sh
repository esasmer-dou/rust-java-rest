#!/usr/bin/env bash
set -euo pipefail

# Production-grade comparison harness.
# Requires: wrk, curl, awk, grep. Optional: docker for container RSS.

FRAMEWORK_URL="${FRAMEWORK_URL:-http://localhost:8080}"
SPRING_URL="${SPRING_URL:-http://localhost:8081}"
FRAMEWORK_CONTAINER="${FRAMEWORK_CONTAINER:-rust-java-rest}"
SPRING_CONTAINER="${SPRING_CONTAINER:-spring-boot-rest}"
CONCURRENCY_LEVELS="${CONCURRENCY_LEVELS:-64 256 512 1000}"
DURATION="${DURATION:-60s}"
WARMUP="${WARMUP:-15s}"
THREADS="${THREADS:-4}"
RESULTS_DIR="${RESULTS_DIR:-benchmark/results/$(date +%Y%m%d_%H%M%S)}"

mkdir -p "$RESULTS_DIR"

PAYLOAD='{"orderId":"ORD-1001","amount":350.75,"paid":true,"address":{"city":"Ankara","street":"Ataturk Cd."},"customer":{"name":"mustafa customer a.ş","email":"mustafa@gmai.com"},"items":[{"name":"test0","price":12.89},{"name":"test1","price":13.89},{"name":"test2","price":14.89}]}'

require_tool() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required tool: $1" >&2
    exit 1
  fi
}

wait_for() {
  local name="$1"
  local url="$2"
  for _ in $(seq 1 60); do
    if curl -fsS "$url/health" >/dev/null 2>&1 || curl -fsS "$url/api/v1/candidates" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "$name is not reachable at $url" >&2
  return 1
}

container_rss() {
  local container="$1"
  if command -v docker >/dev/null 2>&1; then
    docker stats "$container" --no-stream --format '{{.MemUsage}}' 2>/dev/null | awk '{print $1}' || true
  fi
}

scrape_metrics() {
  local name="$1"
  local url="$2"
  curl -fsS "$url/metrics" > "$RESULTS_DIR/${name}_metrics.prom" 2>/dev/null || true
}

run_wrk() {
  local name="$1"
  local method="$2"
  local url="$3"
  local concurrency="$4"
  local output="$RESULTS_DIR/${name}_${method}_c${concurrency}.txt"
  local lua="$RESULTS_DIR/${name}_${method}_c${concurrency}.lua"

  echo "Running ${name} ${method} concurrency=${concurrency}"

  if [[ "$method" == "POST" ]]; then
    cat > "$lua" <<EOF
wrk.method = "POST"
wrk.headers["Content-Type"] = "application/json"
wrk.body = [[$PAYLOAD]]
EOF
    wrk -t"$THREADS" -c"$concurrency" -d"$WARMUP" -s "$lua" "$url" >/dev/null 2>&1 || true
    wrk -t"$THREADS" -c"$concurrency" -d"$DURATION" --latency -s "$lua" "$url" > "$output" 2>&1
  else
    wrk -t"$THREADS" -c"$concurrency" -d"$WARMUP" "$url" >/dev/null 2>&1 || true
    wrk -t"$THREADS" -c"$concurrency" -d"$DURATION" --latency "$url" > "$output" 2>&1
  fi
}

write_summary_header() {
  cat > "$RESULTS_DIR/summary.md" <<EOF
# Rust-Java REST vs Spring Boot Benchmark

- Date: $(date -Is)
- Duration: $DURATION
- Warmup: $WARMUP
- Threads: $THREADS
- Concurrency: $CONCURRENCY_LEVELS
- Framework URL: $FRAMEWORK_URL
- Spring URL: $SPRING_URL

| Target | Method | Concurrency | Requests/sec | P99 | RSS Before | RSS After |
|---|---:|---:|---:|---:|---:|---:|
EOF
}

append_summary() {
  local target="$1"
  local method="$2"
  local concurrency="$3"
  local rss_before="$4"
  local rss_after="$5"
  local file="$RESULTS_DIR/${target}_${method}_c${concurrency}.txt"
  local rps
  local p99
  rps="$(grep 'Requests/sec:' "$file" | awk '{print $2}' || true)"
  p99="$(grep '99%' "$file" | awk '{print $2}' | tail -1 || true)"
  echo "| $target | $method | $concurrency | ${rps:-N/A} | ${p99:-N/A} | ${rss_before:-N/A} | ${rss_after:-N/A} |" >> "$RESULTS_DIR/summary.md"
}

run_target() {
  local target="$1"
  local url="$2"
  local container="$3"

  for concurrency in $CONCURRENCY_LEVELS; do
    local rss_before
    local rss_after

    rss_before="$(container_rss "$container")"
    run_wrk "$target" "GET" "$url/api/v1/candidates" "$concurrency"
    rss_after="$(container_rss "$container")"
    append_summary "$target" "GET" "$concurrency" "$rss_before" "$rss_after"

    rss_before="$(container_rss "$container")"
    run_wrk "$target" "POST" "$url/api/v1/echo" "$concurrency"
    rss_after="$(container_rss "$container")"
    append_summary "$target" "POST" "$concurrency" "$rss_before" "$rss_after"

    scrape_metrics "$target" "$url"
    sleep 5
  done
}

main() {
  require_tool wrk
  require_tool curl

  wait_for "Rust-Java framework" "$FRAMEWORK_URL"
  wait_for "Spring Boot" "$SPRING_URL"

  write_summary_header
  run_target "rust_java" "$FRAMEWORK_URL" "$FRAMEWORK_CONTAINER"
  run_target "spring_boot" "$SPRING_URL" "$SPRING_CONTAINER"

  echo "Benchmark complete: $RESULTS_DIR/summary.md"
}

main "$@"
