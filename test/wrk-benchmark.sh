#!/bin/bash
# wrk Benchmark Script - Spring Boot vs Rust-Spring
#
# Usage: ./wrk-benchmark.sh
#
# Install wrk:
#   Ubuntu/Debian: sudo apt install wrk
#   Windows: Use WSL or Docker
#
# Services:
#   Spring Boot: http://localhost:8888 (Undertow)
#   Rust-Spring: http://localhost:8080 (Hyper)

SPRING_URL="http://localhost:8888"
RUST_URL="http://localhost:8080"

DURATION="10s"
THREADS=4
CONNECTIONS=(10 50 100 200 500)

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
BOLD='\033[1m'
NC='\033[0m'

echo -e "${BOLD}╔═══════════════════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║              WRK BENCHMARK - SPRING BOOT vs RUST-SPRING                       ║${NC}"
echo -e "${BOLD}╚═══════════════════════════════════════════════════════════════════════════════╝${NC}"
echo ""

# Check wrk
if ! command -v wrk &>/dev/null; then
    echo -e "${RED}wrk not found. Install with:${NC}"
    echo "  Ubuntu/Debian: sudo apt install wrk"
    echo "  Windows WSL:   sudo apt install wrk"
    echo "  macOS:         brew install wrk"
    exit 1
fi

# Check services
echo -e "${YELLOW}Checking services...${NC}"
SPRING_OK=$(curl -s -o /dev/null -w "%{http_code}" "$SPRING_URL/order/order" 2>/dev/null)
RUST_OK=$(curl -s -o /dev/null -w "%{http_code}" "$RUST_URL/order/order" 2>/dev/null)

if [ "$SPRING_OK" != "200" ]; then
    echo -e "${RED}Spring Boot not responding at $SPRING_URL${NC}"
    exit 1
fi
if [ "$RUST_OK" != "200" ]; then
    echo -e "${RED}Rust-Spring not responding at $RUST_URL${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Spring Boot running at $SPRING_URL${NC}"
echo -e "${GREEN}✓ Rust-Spring running at $RUST_URL${NC}"
echo ""

# Memory function
get_memory() {
    docker stats --no-stream 2>/dev/null | grep "$1" | awk '{print $4}' | head -1
}

# Show memory
show_memory() {
    echo -e "${MAGENTA}Memory Usage:${NC}"
    echo "  Spring Boot: $(get_memory spring-boot)"
    echo "  Rust-Spring: $(get_memory rust-spring)"
    echo ""
}

# Initial memory
show_memory

# Run benchmark function
run_wrk() {
    local name=$1
    local url=$2
    local connections=$3

    result=$(wrk -t$THREADS -c$connections -d$DURATION --latency "$url" 2>/dev/null)

    # Extract metrics
    rps=$(echo "$result" | grep "Requests/sec" | awk '{printf "%.0f", $2}')
    avg_lat=$(echo "$result" | grep -A1 "Latency" | tail -1 | awk '{print $2}')
    p99=$(echo "$result" | grep "99%" | awk '{print $2}')
    transfer=$(echo "$result" | grep "Transfer/sec" | awk '{print $2}')

    if [ -z "$rps" ]; then
        rps="0"
    fi
    if [ -z "$avg_lat" ]; then
        avg_lat="N/A"
    fi
    if [ -z "$p99" ]; then
        p99="N/A"
    fi

    printf "  %-25s RPS: ${GREEN}%-10s${NC} AvgLat: %-12s P99: %-12s MB/s: %s\n" \
        "$name" "$rps" "$avg_lat" "$p99" "$transfer"
}

# Endpoints
declare -A ENDPOINTS
ENDPOINTS["/order/order"]="Order (19 items)"
ENDPOINTS["/order/test-123"]="Order by ID"
ENDPOINTS["/order/search?status=pending"]="Search"

# Run benchmarks
for conn in "${CONNECTIONS[@]}"; do
    echo -e "${BOLD}═══════════════════════════════════════════════════════════════════════════════${NC}"
    echo -e "${BOLD}  CONNECTIONS: $conn  |  DURATION: $DURATION  |  THREADS: $THREADS${NC}"
    echo -e "${BOLD}═══════════════════════════════════════════════════════════════════════════════${NC}"
    echo ""

    for endpoint in "${!ENDPOINTS[@]}"; do
        name="${ENDPOINTS[$endpoint]}"
        echo "--- $name ---"
        run_wrk "Spring Boot (8888)" "$SPRING_URL$endpoint" "$conn"
        run_wrk "Rust-Spring (8080)" "$RUST_URL$endpoint" "$conn"
        echo ""
    done

    # Memory after test
    show_memory

    sleep 2
done

echo -e "${GREEN}═══════════════════════════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}  BENCHMARK COMPLETED${NC}"
echo -e "${GREEN}═══════════════════════════════════════════════════════════════════════════════${NC}"
