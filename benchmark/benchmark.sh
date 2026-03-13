#!/bin/bash
# Comprehensive Benchmark Script
# Compares Rust-Java REST Framework vs Spring Boot
# Metrics: Memory, RPS, Latency, GC, Object Creation

set -e

# Configuration
FRAMEWORK_HOST="${FRAMEWORK_HOST:-localhost}"
FRAMEWORK_PORT="${FRAMEWORK_PORT:-8080}"
SPRING_HOST="${SPRING_HOST:-localhost}"
SPRING_PORT="${SPRING_PORT:-8081}"
DURATION="${DURATION:-30s}"
CONCURRENCY_LEVELS="10 50 100 1000"
THREADS="${THREADS:-4}"
WARMUP_TIME=5

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Results storage
RESULTS_DIR="/benchmark/results"
mkdir -p "$RESULTS_DIR"

echo "=========================================="
echo "  Rust-Java REST vs Spring Boot Benchmark"
echo "=========================================="
echo ""
echo "Configuration:"
echo "  Duration per test: $DURATION"
echo "  Concurrency levels: $CONCURRENCY_LEVELS"
echo "  Threads: $THREADS"
echo ""

# Function to wait for server
wait_for_server() {
    local host=$1
    local port=$2
    local name=$3
    local max_attempts=30
    local attempt=1

    echo -n "Waiting for $name on $host:$port ... "
    while ! curl -s "http://$host:$port/api/v1/candidates" > /dev/null 2>&1; do
        if [ $attempt -ge $max_attempts ]; then
            echo -e "${RED}FAILED${NC}"
            return 1
        fi
        sleep 1
        attempt=$((attempt + 1))
    done
    echo -e "${GREEN}OK${NC}"
    return 0
}

# Function to get container memory usage
get_memory_mb() {
    local container_name=$1
    if docker stats "$container_name" --no-stream --format "{{.MemUsage}}" 2>/dev/null; then
        docker stats "$container_name" --no-stream --format "{{.MemUsage}}" 2>/dev/null | grep -oP '^\d+\.\d+|\d+' | head -1
    else
        echo "N/A"
    fi
}

# Function to get JVM memory via JMX (if available)
get_jvm_memory() {
    local host=$1
    local port=$2
    # Try to get memory from actuator endpoint (Spring Boot)
    local mem=$(curl -s "http://$host:$port/actuator/metrics/jvm.memory.used" 2>/dev/null | grep -oP '"value":\s*\K[\d.]+' || echo "N/A")
    echo "$mem"
}

# Function to run wrk benchmark
run_benchmark() {
    local name=$1
    local host=$2
    local port=$3
    local endpoint=$4
    local concurrency=$5
    local lua_script=$6

    local url="http://$host:$port$endpoint"
    local output_file="$RESULTS_DIR/${name}_c${concurrency}.txt"

    echo -e "  ${BLUE}Testing $name at concurrency=$concurrency${NC}"

    # Warmup
    if [ $WARMUP_TIME -gt 0 ]; then
        wrk -t1 -c$concurrency -d${WARMUP_TIME}s "$url" > /dev/null 2>&1 || true
        sleep 2
    fi

    # Run benchmark
    if [ -n "$lua_script" ]; then
        wrk -t$THREADS -c$concurrency -d$DURATION -s "$lua_script" "$url" > "$output_file" 2>&1
    else
        wrk -t$THREADS -c$concurrency -d$DURATION "$url" > "$output_file" 2>&1
    fi

    # Parse results
    local rps=$(grep "Requests/sec" "$output_file" | grep -oP '[\d.]+')
    local latency_avg=$(grep -A1 "Latency" "$output_file" | tail -1 | grep -oP '[\d.]+[mu]?s' | head -1)
    local latency_99=$(grep "99%" "$output_file" | grep -oP '[\d.]+[mu]s?' || echo "N/A")
    local errors=$(grep "Socket errors" "$output_file" || echo "0")

    echo "    RPS: $rps"
    echo "    Latency (avg): $latency_avg"
    echo "    Latency (99%): $latency_99"

    # Return results as JSON-like string
    echo "{\"name\":\"$name\",\"concurrency\":$concurrency,\"rps\":$rps,\"latency_avg\":\"$latency_avg\",\"latency_99\":\"$latency_99\"}"
}

# Function to run full benchmark suite
run_suite() {
    local name=$1
    local host=$2
    local port=$3
    local container=$4

    echo ""
    echo -e "${YELLOW}=== Benchmarking $name ===${NC}"
    echo ""

    for concurrency in $CONCURRENCY_LEVELS; do
        echo ""
        echo "--- Concurrency: $concurrency ---"

        # Get memory before
        local mem_before=$(get_memory_mb "$container")
        echo "  Memory before: ${mem_before}MiB"

        # Run GET benchmark
        run_benchmark "${name}_get" "$host" "$port" "/api/v1/candidates" "$concurrency" ""

        # Run POST benchmark
        run_benchmark "${name}_post" "$host" "$port" "/api/v1/echo" "$concurrency" "/benchmark/benchmark_post.lua"

        # Get memory after
        local mem_after=$(get_memory_mb "$container")
        echo "  Memory after: ${mem_after}MiB"

        # Cool down
        sleep 5
    done
}

# Main execution
main() {
    echo ""
    echo "Starting benchmark suite..."
    echo ""

    # Check if wrk is available
    if ! command -v wrk &> /dev/null; then
        echo -e "${RED}Error: wrk is not installed${NC}"
        exit 1
    fi

    # Benchmark Rust-Java Framework
    if wait_for_server "$FRAMEWORK_HOST" "$FRAMEWORK_PORT" "Rust-Java Framework"; then
        run_suite "Rust-Java" "$FRAMEWORK_HOST" "$FRAMEWORK_PORT" "rust-java-rest"
    else
        echo -e "${YELLOW}Warning: Rust-Java Framework not available${NC}"
    fi

    # Benchmark Spring Boot
    if wait_for_server "$SPRING_HOST" "$SPRING_PORT" "Spring Boot"; then
        run_suite "Spring-Boot" "$SPRING_HOST" "$SPRING_PORT" "spring-boot-rest"
    else
        echo -e "${YELLOW}Warning: Spring Boot not available${NC}"
    fi

    echo ""
    echo "=========================================="
    echo "  Benchmark Complete"
    echo "=========================================="
    echo ""
    echo "Results saved to: $RESULTS_DIR"
    echo ""

    # Print summary
    echo "=== Summary ==="
    for f in "$RESULTS_DIR"/*.txt; do
        if [ -f "$f" ]; then
            echo "--- $(basename $f) ---"
            cat "$f"
            echo ""
        fi
    done
}

# Run main
main "$@"
