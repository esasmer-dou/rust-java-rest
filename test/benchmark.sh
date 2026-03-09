#!/bin/bash
# Benchmark Script - wrk ile performans testi
# Oncelikle wrk kurulu olmali: https://github.com/wg/wrk

BASE_URL="http://localhost:8080"
THREADS=4
CONNECTIONS=100
DURATION=30s

echo "=== Rust-Spring Performance Benchmark ==="
echo "URL: $BASE_URL"
echo "Threads: $THREADS"
echo "Connections: $CONNECTIONS"
echo "Duration: $DURATION"
echo ""

# wrk kontrol
if ! command -v wrk &> /dev/null; then
    echo "ERROR: wrk kurulu degil!"
    echo "Ubuntu/Debian: sudo apt install wrk"
    echo "Mac: brew install wrk"
    exit 1
fi

echo "1. Health Check Benchmark"
echo "---"
wrk -t$THREADS -c$CONNECTIONS -d$DURATION "$BASE_URL/health"
echo ""

echo "2. GET /order/order Benchmark"
echo "---"
wrk -t$THREADS -c$CONNECTIONS -d$DURATION "$BASE_URL/order/order"
echo ""

echo "3. Pattern Route Benchmark (GET /order/{id})"
echo "---"
wrk -t$THREADS -c$CONNECTIONS -d$DURATION "$BASE_URL/order/12345"
echo ""

echo "4. Query String Benchmark (GET /order/search)"
echo "---"
wrk -t$THREADS -c$CONNECTIONS -d$DURATION "$BASE_URL/order/search?status=pending&page=1"
echo ""

echo "=== Benchmark Complete ==="
