#!/bin/bash
# Benchmark Script - Spring Boot vs Rust-Spring
#
# Usage: ./benchmark.sh
#
# Requires: wrk (sudo apt install wrk) or Apache Bench (ab)
#
# Services:
#   Spring Boot: http://localhost:8888 (Undertow)
#   Rust-Spring: http://localhost:8080 (Hyper)

SPRING_BOOT_URL="http://localhost:8888"
RUST_SPRING_URL="http://localhost:8080"

DURATION="10s"
THREADS=4
CONNECTIONS=(10 50 100 500)

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
BOLD='\033[1m'
NC='\033[0m'

echo -e "${BOLD}╔═════════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║       SPRING BOOT vs RUST-SPRING BENCHMARK (OpenJ9)                ║${NC}"
echo -e "${BOLD}╚═════════════════════════════════════════════════════════════════════╝${NC}"
echo ""

# Check wrk availability
check_wrk() {
    if command -v wrk &>/dev/null; then
        return 0
    fi
    echo -e "${RED}wrk not found. Install with: sudo apt install wrk${NC}"
    return 1
}

# Check if services are running
echo -e "${YELLOW}Checking services...${NC}"

SPRING_HEALTH=$(curl -s -o /dev/null -w "%{http_code}" "$SPRING_BOOT_URL/actuator/health" 2>/dev/null)
RUST_HEALTH=$(curl -s -o /dev/null -w "%{http_code}" "$RUST_SPRING_URL/health" 2>/dev/null)

if [ "$SPRING_HEALTH" != "200" ]; then
    echo -e "${RED}Spring Boot not responding at $SPRING_BOOT_URL${NC}"
    echo "  Start with: docker-compose up -d spring-boot"
    echo "  Or locally: cd spring-boot-simple-rest-api && mvn spring-boot:run"
fi

if [ "$RUST_HEALTH" != "200" ]; then
    echo -e "${RED}Rust-Spring not responding at $RUST_SPRING_URL${NC}"
    echo "  Start with: docker-compose up -d rust-spring"
    echo "  Or locally: cd rust-spring-boot && java -cp target/rust-spring-1.0.0.jar:target/lib/* com.reactor.rust.ReactorRustHyperApplication"
fi

if [ "$SPRING_HEALTH" != "200" ] || [ "$RUST_HEALTH" != "200" ]; then
    exit 1
fi

echo -e "${GREEN}Both services are running!${NC}"
echo ""

# Show container memory if docker
show_memory() {
    echo -e "${MAGENTA}Container Memory Usage:${NC}"
    docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}" 2>/dev/null | grep -E "NAME|spring|rust" || echo "  (Docker not running)"
    echo ""
}

# Function to run wrk benchmark
run_benchmark() {
    local name=$1
    local url=$2
    local connections=$3

    result=$(wrk -t$THREADS -c$connections -d$DURATION "$url" 2>/dev/null)

    rps=$(echo "$result" | grep "Requests/sec" | awk '{printf "%.0f", $2}')
    latency=$(echo "$result" | grep -A1 "Latency" | tail -1 | awk '{print $2}')
    p99=$(echo "$result" | grep "99%" | awk '{print $2}')

    if [ -z "$rps" ]; then
        rps="0"
    fi
    if [ -z "$latency" ]; then
        latency="N/A"
    fi
    if [ -z "$p99" ]; then
        p99="N/A"
    fi

    printf "  %-25s " "$name"
    printf "RPS: ${GREEN}%-10s${NC} " "$rps"
    printf "Latency: %-10s " "$latency"
    printf "P99: %s\n" "$p99"
}

# Check wrk
if ! check_wrk; then
    exit 1
fi

# Show initial memory
show_memory

# Run benchmarks
for conn in "${CONNECTIONS[@]}"; do
    echo -e "${BOLD}═════════════════════════════════════════════════════════════════════${NC}"
    echo -e "${BOLD}  CONNECTIONS: $conn  |  DURATION: $DURATION  |  THREADS: $THREADS${NC}"
    echo -e "${BOLD}═════════════════════════════════════════════════════════════════════${NC}"
    echo ""

    echo "--- GET /order/order (19 items JSON) ---"
    run_benchmark "Spring Boot (Undertow:8888)" "$SPRING_BOOT_URL/order/order" "$conn"
    run_benchmark "Rust-Spring (Hyper:8080)" "$RUST_SPRING_URL/order/order" "$conn"
    echo ""

    echo "--- GET /order/{id} (path param) ---"
    run_benchmark "Spring Boot (Undertow:8888)" "$SPRING_BOOT_URL/order/test-123" "$conn"
    run_benchmark "Rust-Spring (Hyper:8080)" "$RUST_SPRING_URL/order/test-123" "$conn"
    echo ""

    echo "--- GET /order/search (query string) ---"
    run_benchmark "Spring Boot (Undertow:8888)" "$SPRING_BOOT_URL/order/search?status=pending&page=1" "$conn"
    run_benchmark "Rust-Spring (Hyper:8080)" "$RUST_SPRING_URL/order/search?status=pending&page=1" "$conn"
    echo ""

    echo "--- GET /api/v1/candidates (benchmark endpoint) ---"
    run_benchmark "Spring Boot (Undertow:8888)" "$SPRING_BOOT_URL/api/v1/candidates" "$conn"
    run_benchmark "Rust-Spring (Hyper:8080)" "$RUST_SPRING_URL/api/v1/candidates" "$conn"
    echo ""

    sleep 2
done

# Show final memory
echo -e "${BOLD}═════════════════════════════════════════════════════════════════════${NC}"
echo -e "${BOLD}  FINAL MEMORY STATE${NC}"
echo -e "${BOLD}═════════════════════════════════════════════════════════════════════${NC}"
echo ""
show_memory

echo -e "${GREEN}═════════════════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}  BENCHMARK COMPLETED${NC}"
echo -e "${GREEN}═════════════════════════════════════════════════════════════════════${NC}"
