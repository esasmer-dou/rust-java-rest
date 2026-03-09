#!/bin/bash
# Linux/Mac Bash Test Script
# Rust-Spring Performance API Testleri

BASE_URL="http://localhost:8080"

echo "=== Rust-Spring Performance API Tests ==="
echo "Base URL: $BASE_URL"
echo ""

# Renkler
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# 1. Health Check
echo -e "${YELLOW}1. Health Check (GET /health)${NC}"
response=$(curl -s "$BASE_URL/health")
echo -e "   Response: ${GREEN}$response${NC}"
echo ""

# 2. Get Order Info
echo -e "${YELLOW}2. Get Order Info (GET /order/order)${NC}"
response=$(curl -s "$BASE_URL/order/order")
echo -e "   Response: ${GREEN}$response${NC}"
echo ""

# 3. Create Order
echo -e "${YELLOW}3. Create Order (POST /order/create)${NC}"
body='{"orderId":"ORD-999","amount":150.50,"paid":false}'
response=$(curl -s -X POST "$BASE_URL/order/create" \
    -H "Content-Type: application/json" \
    -d "$body")
echo -e "   Request: $body"
echo -e "   Response: ${GREEN}$response${NC}"
echo ""

# 4. Get Order By ID (Pattern Route)
echo -e "${YELLOW}4. Get Order By ID (GET /order/{id})${NC}"
response=$(curl -s "$BASE_URL/order/12345")
echo -e "   Response: ${GREEN}$response${NC}"
echo ""

# 5. Search Orders (Query String)
echo -e "${YELLOW}5. Search Orders (GET /order/search?status=pending&page=1)${NC}"
response=$(curl -s "$BASE_URL/order/search?status=pending&page=1")
echo -e "   Response: ${GREEN}$response${NC}"
echo ""

echo -e "${GREEN}=== Tests Complete ===${NC}"
