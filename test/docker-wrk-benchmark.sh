#!/bin/bash
# Docker-based wrk Benchmark - Spring Boot vs Rust-Spring
#
# Uses wrk in a Docker container for portability
#
# Usage:
#   bash test/docker-wrk-benchmark.sh
#
# Services:
#   Spring Boot: http://localhost:8888 (Undertow)
#   Rust-Spring: http://localhost:8080 (Hyper)

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
BOLD='\033[1m'
NC='\033[0m'

# Configuration
SPRING_URL="host.docker.internal:8888"
RUST_URL="host.docker.internal:8080"
DURATION="10s"
THREADS=4
CONNECTIONS=(10 50 100 200 500)

# WRK Docker image
WRK_IMAGE="williamyeh/wrk:alpine"

echo -e "${BOLD}╔═══════════════════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║           DOCKER WRK BENCHMARK - SPRING BOOT vs RUST-SPRING                   ║${NC}"
echo -e "${BOLD}╚═══════════════════════════════════════════════════════════════════════════════╝${NC}"
echo ""

# Pull wrk image if not exists
echo -e "${YELLOW}Checking wrk Docker image...${NC}"
docker pull $WRK_IMAGE >/dev/null 2>&1
echo -e "${GREEN}✓ wrk image ready${NC}"
echo ""

# Check target services (from host)
echo -e "${YELLOW}Checking services...${NC}"
SPRING_OK=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8888/order/order" 2>/dev/null)
RUST_OK=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8080/order/order" 2>/dev/null)

if [ "$SPRING_OK" != "200" ]; then
    echo -e "${RED}Spring Boot not responding at localhost:8888${NC}"
    exit 1
fi
if [ "$RUST_OK" != "200" ]; then
    echo -e "${RED}Rust-Spring not responding at localhost:8080${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Spring Boot running${NC}"
echo -e "${GREEN}✓ Rust-Spring running${NC}"
echo ""

# Memory function
get_memory() {
    docker stats --no-stream 2>/dev/null | grep "$1" | awk '{print $4}' | head -1
}

# Show memory
show_memory() {
    echo -e "${MAGENTA}Memory Usage:${NC}"
    printf "  %-20s %s\n" "Spring Boot:" "$(get_memory spring-boot)"
    printf "  %-20s %s\n" "Rust-Spring:" "$(get_memory rust-spring)"
    echo ""
}

# Run wrk benchmark
run_wrk() {
    local name=$1
    local url=$2
    local connections=$3

    result=$(docker run --rm $WRK_IMAGE wrk -t$THREADS -c$connections -d$DURATION --latency "$url" 2>/dev/null)

    # Extract metrics
    rps=$(echo "$result" | grep "Requests/sec" | awk '{printf "%.0f", $2}')
    avg_lat=$(echo "$result" | grep -A1 "Latency" | tail -1 | awk '{print $2}')
    p99=$(echo "$result" | grep "99%" | awk '{print $2}')
    transfer=$(echo "$result" | grep "Transfer/sec" | awk '{print $2}')

    if [ -z "$rps" ]; then
        rps="0"
    fi

    printf "  %-25s RPS: ${GREEN}%-10s${NC} Latency: %-12s P99: %-12s\n" \
        "$name" "$rps" "$avg_lat" "$p99"
}

# Initial memory
echo "=== Initial Memory ==="
show_memory

# Endpoints
declare -A ENDPOINTS
ENDPOINTS["/order/order"]="Order (19 items JSON)"
ENDPOINTS["/order/test-123"]="Order by ID"
ENDPOINTS["/order/search?status=pending"]="Search Query"

# Run benchmarks
for conn in "${CONNECTIONS[@]}"; do
    echo -e "${BOLD}═══════════════════════════════════════════════════════════════════════════════${NC}"
    echo -e "${BOLD}  CONNECTIONS: $conn  |  DURATION: $DURATION  |  THREADS: $THREADS${NC}"
    echo -e "${BOLD}═══════════════════════════════════════════════════════════════════════════════${NC}"
    echo ""

    for endpoint in "${!ENDPOINTS[@]}"; do
        name="${ENDPOINTS[$endpoint]}"
        echo "--- $name ---"
        run_wrk "Spring Boot (8888)" "http://$SPRING_URL$endpoint" "$conn"
        run_wrk "Rust-Spring (8080)" "http://$RUST_URL$endpoint" "$conn"
        echo ""
    done

    show_memory
    sleep 2
done

echo -e "${GREEN}═══════════════════════════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}  BENCHMARK COMPLETED${NC}"
echo -e "${GREEN}═══════════════════════════════════════════════════════════════════════════════${NC}"
