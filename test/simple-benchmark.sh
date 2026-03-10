#!/bin/bash
# Simple Benchmark - Spring Boot vs Rust-Spring
# Uses curl with timing - no external tools required

SPRING_URL="http://localhost:8888"
RUST_URL="http://localhost:8080"
REQUESTS=100

echo "╔═══════════════════════════════════════════════════════════════════════════════╗"
echo "║         SIMPLE BENCHMARK - SPRING BOOT vs RUST-SPRING (curl-based)            ║"
echo "╚═══════════════════════════════════════════════════════════════════════════════╝"
echo ""

# Check services
SPRING_OK=$(curl -s -o /dev/null -w "%{http_code}" "$SPRING_URL/order/order" 2>/dev/null)
RUST_OK=$(curl -s -o /dev/null -w "%{http_code}" "$RUST_URL/order/order" 2>/dev/null)

if [ "$SPRING_OK" != "200" ] || [ "$RUST_OK" != "200" ]; then
    echo "ERROR: Services not responding"
    exit 1
fi

echo "✓ Spring Boot: $SPRING_URL"
echo "✓ Rust-Spring: $RUST_URL"
echo ""

# Memory function
get_memory() {
    docker stats --no-stream 2>/dev/null | grep "$1" | awk '{print $4}' | head -1
}

# Benchmark function using curl timing
benchmark() {
    local url=$1
    local requests=$2

    local total_time=0
    local success=0

    for i in $(seq 1 $requests); do
        result=$(curl -s -o /dev/null -w "%{time_total}" "$url" 2>/dev/null)
        if [ $? -eq 0 ]; then
            total_time=$(awk "BEGIN {print $total_time + $result}")
            success=$((success + 1))
        fi
    done

    if [ $success -gt 0 ]; then
        local avg_time=$(awk "BEGIN {printf \"%.3f\", $total_time / $success}")
        local rps=$(awk "BEGIN {printf \"%.1f\", $success / $total_time}")
        echo "$rps|$avg_time"
    else
        echo "0|0"
    fi
}

echo "=== Initial Memory ==="
echo "Spring Boot: $(get_memory spring-boot)"
echo "Rust-Spring: $(get_memory rust-spring)"
echo ""

echo "=== Running $REQUESTS sequential requests per endpoint ==="
echo ""

# Test endpoints
declare -A ENDPOINTS
ENDPOINTS["/order/order"]="Order (19 items)"
ENDPOINTS["/order/test-123"]="Order by ID"
ENDPOINTS["/order/search?status=pending"]="Search"

for endpoint in "${!ENDPOINTS[@]}"; do
    name="${ENDPOINTS[$endpoint]}"
    echo "--- $name ---"

    printf "  %-20s " "Spring Boot:"
    result=$(benchmark "$SPRING_URL$endpoint" $REQUESTS)
    rps=$(echo $result | cut -d'|' -f1)
    lat=$(echo $result | cut -d'|' -f2)
    echo "RPS: $rps  Avg Latency: ${lat}s"

    printf "  %-20s " "Rust-Spring:"
    result=$(benchmark "$RUST_URL$endpoint" $REQUESTS)
    rps=$(echo $result | cut -d'|' -f1)
    lat=$(echo $result | cut -d'|' -f2)
    echo "RPS: $rps  Avg Latency: ${lat}s"

    echo ""
done

echo "=== Final Memory ==="
echo "Spring Boot: $(get_memory spring-boot)"
echo "Rust-Spring: $(get_memory rust-spring)"
echo ""

echo "═══════════════════════════════════════════════════════════════════════════════"
echo "  BENCHMARK COMPLETED"
echo "═══════════════════════════════════════════════════════════════════════════════"
