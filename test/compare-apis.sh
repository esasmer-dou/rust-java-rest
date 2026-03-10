#!/bin/bash
# API Comparison Test - Spring Boot vs Rust-Spring
#
# Spring Boot: Port 8888 (Undertow)
# Rust-Spring: Port 8080 (Hyper)

SPRING_URL="http://localhost:8888"
RUST_URL="http://localhost:8080"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

echo -e "${BOLD}╔═══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║     SPRING BOOT vs RUST-SPRING API COMPARISON                ║${NC}"
echo -e "${BOLD}╚═══════════════════════════════════════════════════════════════╝${NC}"
echo ""

# Test function
test_endpoint() {
    local name=$1
    local spring_url=$2
    local rust_url=$3

    echo -e "${CYAN}=== $name ===${NC}"

    # Spring Boot
    echo -n "  Spring Boot (8888): "
    spring_result=$(curl -s -w "\n%{http_code}" "$spring_url" 2>/dev/null)
    spring_code=$(echo "$spring_result" | tail -1)
    spring_body=$(echo "$spring_result" | head -n -1 | head -c 100)
    if [ "$spring_code" = "200" ]; then
        echo -e "${GREEN}✓ 200${NC} - ${spring_body}..."
    else
        echo -e "${RED}✗ $spring_code${NC}"
    fi

    # Rust-Spring
    echo -n "  Rust-Spring (8080): "
    rust_result=$(curl -s -w "\n%{http_code}" "$rust_url" 2>/dev/null)
    rust_code=$(echo "$rust_result" | tail -1)
    rust_body=$(echo "$rust_result" | head -n -1 | head -c 100)
    if [ "$rust_code" = "200" ]; then
        echo -e "${GREEN}✓ 200${NC} - ${rust_body}..."
    else
        echo -e "${RED}✗ $rust_code${NC}"
    fi

    echo ""
}

# POST test function
test_post() {
    local name=$1
    local spring_url=$2
    local rust_url=$3
    local data=$4

    echo -e "${CYAN}=== $name (POST) ===${NC}"

    # Spring Boot
    echo -n "  Spring Boot (8888): "
    spring_result=$(curl -s -w "\n%{http_code}" -X POST -H "Content-Type: application/json" -d "$data" "$spring_url" 2>/dev/null)
    spring_code=$(echo "$spring_result" | tail -1)
    spring_body=$(echo "$spring_result" | head -n -1 | head -c 100)
    if [ "$spring_code" = "200" ]; then
        echo -e "${GREEN}✓ 200${NC} - ${spring_body}..."
    else
        echo -e "${RED}✗ $spring_code${NC}"
    fi

    # Rust-Spring
    echo -n "  Rust-Spring (8080): "
    rust_result=$(curl -s -w "\n%{http_code}" -X POST -H "Content-Type: application/json" -d "$data" "$rust_url" 2>/dev/null)
    rust_code=$(echo "$rust_result" | tail -1)
    rust_body=$(echo "$rust_result" | head -n -1 | head -c 100)
    if [ "$rust_code" = "200" ]; then
        echo -e "${GREEN}✓ 200${NC} - ${rust_body}..."
    else
        echo -e "${RED}✗ $rust_code${NC}"
    fi

    echo ""
}

echo -e "${YELLOW}Testing all endpoints...${NC}"
echo ""

# Health checks
test_endpoint "Health Check" "$SPRING_URL/actuator/health" "$RUST_URL/health"

# Order API endpoints
test_endpoint "GET /order/order" "$SPRING_URL/order/order" "$RUST_URL/order/order"
test_endpoint "GET /order/{id}" "$SPRING_URL/order/test-123" "$RUST_URL/order/test-123"
test_endpoint "GET /order/search" "$SPRING_URL/order/search?status=pending&page=1" "$RUST_URL/order/search?status=pending&page=1"

# POST endpoints
test_post "POST /order/create" "$SPRING_URL/order/create" "$RUST_URL/order/create" '{"orderId":12345,"amount":99.99}'
test_post "POST /order/cancel" "$SPRING_URL/order/cancel" "$RUST_URL/order/cancel" '{"orderId":12345,"amount":99.99}'

# Benchmark API endpoints (/api/v1/*)
test_endpoint "GET /api/v1/candidates" "$SPRING_URL/api/v1/candidates" "$RUST_URL/api/v1/candidates"
test_post "POST /api/v1/echo" "$SPRING_URL/api/v1/echo" "$RUST_URL/api/v1/echo" '{"orderId":"TEST-123","amount":99.99,"paid":true,"address":{"city":"Istanbul","street":"Main St"},"customer":{"name":"John","email":"john@test.com"},"items":[]}'

echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}  COMPARISON TEST COMPLETED${NC}"
echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}"
