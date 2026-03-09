#!/bin/bash
# Simple API Test Script for Rust-Spring Performance
# Tests all API endpoints once

BASE_URL="http://localhost:8080"

echo "=== Rust-Spring Simple API Test ==="
echo "Base URL: $BASE_URL"
echo ""

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

# Test counter
PASS=0
FAIL=0

test_api() {
    local name="$1"
    local method="$2"
    local endpoint="$3"
    local data="$4"
    local content_type="$5"

    echo -e "${YELLOW}Testing: $name${NC}"

    if [ -n "$data" ]; then
        if [ -n "$content_type" ]; then
            response=$(curl -s -w "\n%{http_code}" -X "$method" "$BASE_URL$endpoint" \
                -H "Content-Type: $content_type" \
                -d "$data" 2>/dev/null)
        else
            response=$(curl -s -w "\n%{http_code}" -X "$method" "$BASE_URL$endpoint" \
                -d "$data" 2>/dev/null)
        fi
    else
        response=$(curl -s -w "\n%{http_code}" -X "$method" "$BASE_URL$endpoint" 2>/dev/null)
    fi

    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')

    if [ "$http_code" -ge 200 ] && [ "$http_code" -lt 300 ]; then
        echo -e "   Status: ${GREEN}$http_code${NC}"
        echo -e "   Response: ${GREEN}$body${NC}"
        ((PASS++))
    else
        echo -e "   Status: ${RED}$http_code${NC}"
        echo -e "   Response: ${RED}$body${NC}"
        ((FAIL++))
    fi
    echo ""
}

# 1. Health Check
test_api "Health Check (GET /health)" "GET" "/health"

# 2. Get Order Info
test_api "Get Order Info (GET /order/order)" "GET" "/order/order"

# 3. Create Order (Simple)
test_api "Create Order Simple (POST /order/create)" "POST" "/order/create" \
    '{"orderId":"ORD-999","amount":150.50,"paid":false}' "application/json"

# 4. Create Order (Full - with headers)
test_api "Create Order Full (POST /order/create)" "POST" "/order/create" \
    '{"orderId":"ORD-1000","amount":250.75,"paid":true}' "application/json"

# 5. Get Order By ID (Pattern Route)
test_api "Get Order By ID (GET /order/{id})" "GET" "/order/12345"

# 6. Search Orders (Query String)
test_api "Search Orders (GET /order/search)" "GET" "/order/search?status=pending&page=1"

# Summary
echo "================================"
echo -e "${GREEN}Passed: $PASS${NC}"
echo -e "${RED}Failed: $FAIL${NC}"
echo "================================"

if [ $FAIL -eq 0 ]; then
    echo -e "${GREEN}All tests passed!${NC}"
    exit 0
else
    echo -e "${RED}Some tests failed!${NC}"
    exit 1
fi
