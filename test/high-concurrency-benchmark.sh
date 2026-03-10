#!/bin/bash
# High Concurrency Benchmark - Spring Boot vs Rust-Spring
#
# Measures: RPS, Latency, Memory Usage
# Concurrency: 10, 25, 100, 1000

SPRING_URL="http://localhost:8888"
RUST_URL="http://localhost:8080"
ITERATIONS=1000

echo "╔═══════════════════════════════════════════════════════════════════════════════╗"
echo "║           HIGH CONCURRENCY BENCHMARK - SPRING BOOT vs RUST-SPRING             ║"
echo "╚═══════════════════════════════════════════════════════════════════════════════╝"
echo ""

# Check services
echo "=== Checking Services ==="
SPRING_OK=$(curl -s -o /dev/null -w "%{http_code}" "$SPRING_URL/order/order" 2>/dev/null)
RUST_OK=$(curl -s -o /dev/null -w "%{http_code}" "$RUST_URL/order/order" 2>/dev/null)

if [ "$SPRING_OK" != "200" ]; then
    echo "ERROR: Spring Boot not responding at $SPRING_URL"
    exit 1
fi
if [ "$RUST_OK" != "200" ]; then
    echo "ERROR: Rust-Spring not responding at $RUST_URL"
    exit 1
fi
echo "✓ Spring Boot running at $SPRING_URL"
echo "✓ Rust-Spring running at $RUST_URL"
echo ""

# Memory check function
get_memory() {
    docker stats --no-stream 2>/dev/null | grep "$1" | awk '{print $4}'
}

# Benchmark function
run_benchmark() {
    local name=$1
    local url=$2
    local concurrency=$3
    local endpoint=$4

    local start_time=$(date +%s%N)

    for i in $(seq 1 $ITERATIONS); do
        curl -s "$url$endpoint" > /dev/null &
        if [ $((i % concurrency)) -eq 0 ]; then
            wait
        fi
    done
    wait

    local end_time=$(date +%s%N)
    local duration_ms=$(( (end_time - start_time) / 1000000 ))
    local rps=$(awk "BEGIN {printf \"%.1f\", $ITERATIONS * 1000 / $duration_ms}")
    local avg_latency=$(awk "BEGIN {printf \"%.2f\", $duration_ms / $ITERATIONS}")

    local mem=$(get_memory "$name")

    printf "%-20s RPS: %-8s Latency: %-10s Memory: %s\n" "$name" "$rps" "${avg_latency}ms" "$mem"
}

# Endpoints to test
ENDPOINTS=(
    "/order/order|Order (19 items)"
    "/order/test-123|Order by ID"
    "/order/search?status=pending|Search"
)

# Concurrency levels
CONCURRENCY=(10 25 100 1000)

# Initial memory
echo "=== Initial Memory Usage ==="
echo "Spring Boot: $(get_memory spring-boot)"
echo "Rust-Spring: $(get_memory rust-spring)"
echo ""

# Run benchmarks
for conn in "${CONCURRENCY[@]}"; do
    echo "═══════════════════════════════════════════════════════════════════════════════"
    echo "  CONCURRENCY: $conn | ITERATIONS: $ITERATIONS"
    echo "═══════════════════════════════════════════════════════════════════════════════"

    for endpoint_data in "${ENDPOINTS[@]}"; do
        IFS='|' read -r endpoint name <<< "$endpoint_data"

        echo ""
        echo "--- $name ---"
        run_benchmark "spring-boot" "$SPRING_URL" "$conn" "$endpoint"
        run_benchmark "rust-spring" "$RUST_URL" "$conn" "$endpoint"
    done

    echo ""

    # Memory after each concurrency level
    echo "--- Memory After Concurrency $conn ---"
    echo "Spring Boot: $(get_memory spring-boot)"
    echo "Rust-Spring: $(get_memory rust-spring)"
    echo ""

    sleep 3
done

echo "═══════════════════════════════════════════════════════════════════════════════"
echo "  BENCHMARK COMPLETED"
echo "═══════════════════════════════════════════════════════════════════════════════"
