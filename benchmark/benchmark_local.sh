#!/bin/bash
# Comprehensive Local Benchmark Script
# Tests Rust-Java Framework vs Spring Boot

echo "# Benchmark Results"
echo "Date: $(date)"
echo ""

FRAMEWORK_URL="http://localhost:8080"
SPRING_URL="http://localhost:8081"
CONCURRENCY_LEVELS="10 50 100 1000"
REQUESTS_PER_LEVEL="10000 10000 10000 5000"

# Function to run benchmark
run_benchmark() {
    local name=$1
    local url=$2
    local concurrency=$3
    local requests=$4

    echo "## $name - Concurrency: $concurrency, Requests: $requests"
    echo ""

    # Memory before
    local mem_before=$(ps aux 2>/dev/null | grep java | grep -v grep | awk '{sum+=$6} END {print sum/1024}' || echo "N/A")

    # Run concurrent requests
    local start_time=$(date +%s.%N)

    # Use xargs for parallel execution
    seq $requests | xargs -P$concurrency -I{} curl -s -o /dev/null -w "%{http_code}\n" "$url" 2>/dev/null | grep -c "200" || echo "0"

    local end_time=$(date +%s.%N)
    local duration=$(echo "$end_time - $start_time" | bc)

    # Memory after
    local mem_after=$(ps aux 2>/dev/null | grep java | grep -v grep | awk '{sum+=$6} END {print sum/1024}' || echo "N/A")

    local rps=$(echo "scale=2; $requests / $duration" | bc)

    echo "| Metric | Value |"
    echo "|--------|-------|"
    echo "| RPS | $rps |"
    echo "| Duration | ${duration}s |"
    echo "| Concurrency | $concurrency |"
    echo "| Memory Before | ${mem_before} MB |"
    echo "| Memory After | ${mem_after} MB |"
    echo ""
}

# Run benchmarks
echo "## Rust-Java Framework Benchmarks"
echo ""

for i in 1 2 3 4; do
    concurrency=$(echo $CONCURRENCY_LEVELS | cut -d' ' -f$i)
    requests=$(echo $REQUESTS_PER_LEVEL | cut -d' ' -f$i)
    run_benchmark "GET /api/v1/candidates" "$FRAMEWORK_URL/api/v1/candidates" $concurrency $requests
done

# Test Spring Boot if available
if curl -s -o /dev/null "$SPRING_URL/actuator/health" 2>/dev/null; then
    echo "## Spring Boot Benchmarks"
    echo ""
    for i in 1 2 3 4; do
        concurrency=$(echo $CONCURRENCY_LEVELS | cut -d' ' -f$i)
        requests=$(echo $REQUESTS_PER_LEVEL | cut -d' ' -f$i)
        run_benchmark "GET /api/v1/candidates" "$SPRING_URL/api/v1/candidates" $concurrency $requests
    done
fi

echo ""
echo "## Summary"
echo "Benchmark complete."
