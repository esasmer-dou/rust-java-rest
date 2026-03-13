#!/bin/bash
# Local Benchmark Script for Windows/WSL
# Runs benchmarks using curl and collects metrics

set -e

# Configuration
FRAMEWORK_URL="${FRAMEWORK_URL:-http://localhost:8080}"
SPRING_URL="${SPRING_URL:-http://localhost:8081}"
REQUESTS="${REQUESTS:-10000}"
CONCURRENCY_LEVELS="10 50 100 1000"

# Results
RESULTS_FILE="benchmark_results_$(date +%Y%m%d_%H%M%S).md"

echo "# Benchmark Results - $(date)" > "$RESULTS_FILE"
echo "" >> "$RESULTS_FILE"

# Function to measure memory from Docker container
get_docker_memory() {
    local container=$1
    docker stats "$container" --no-stream --format "{{.MemUsage}}" 2>/dev/null || echo "N/A"
}

# Function to run simple HTTP benchmark with curl
run_curl_benchmark() {
    local name=$1
    local url=$2
    local concurrency=$3
    local requests=$4

    echo "Benchmarking $name at $url with concurrency=$concurrency, requests=$requests"

    local start_time=$(date +%s.%N)

    # Simple concurrent requests using background processes
    for i in $(seq 1 $requests); do
        curl -s -o /dev/null "$url" &
        if [ $((i % concurrency)) -eq 0 ]; then
            wait
        fi
    done
    wait

    local end_time=$(date +%s.%N)
    local duration=$(echo "$end_time - $start_time" | bc)
    local rps=$(echo "scale=2; $requests / $duration" | bc)

    echo "  Duration: ${duration}s"
    echo "  RPS: $rps"
    echo ""

    echo "$rps"
}

# Function to run wrk if available
run_wrk_benchmark() {
    local name=$1
    local url=$2
    local concurrency=$3
    local duration="${4:-30s}"

    if command -v wrk &> /dev/null; then
        echo "Running wrk for $name at $url with c=$concurrency, d=$duration"
        wrk -t4 -c$concurrency -d$duration "$url"
    else
        echo "wrk not available, using curl fallback"
        run_curl_benchmark "$name" "$url" "$concurrency" 1000
    fi
}

echo "=========================================="
echo "  Load Test Benchmark"
echo "=========================================="
echo ""

# Test framework endpoints
for concurrency in $CONCURRENCY_LEVELS; do
    echo ""
    echo "--- Concurrency Level: $concurrency ---"
    echo ""

    # Rust-Java Framework
    echo "### Rust-Java Framework ###"
    run_wrk_benchmark "Rust-Java GET" "$FRAMEWORK_URL/api/v1/candidates" "$concurrency"
    echo ""

    # Spring Boot (if available)
    if curl -s "$SPRING_URL/actuator/health" > /dev/null 2>&1; then
        echo "### Spring Boot ###"
        run_wrk_benchmark "Spring Boot GET" "$SPRING_URL/api/v1/candidates" "$concurrency"
        echo ""
    fi
done

echo ""
echo "Results saved to: $RESULTS_FILE"
