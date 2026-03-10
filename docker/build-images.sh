#!/bin/bash
# Build Docker Images - Spring Boot vs Rust-Spring
#
# This script builds both Docker images for performance comparison

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "=================================================="
echo "  Building Docker Images"
echo "=================================================="
echo ""

# ============================================
# Build Spring Boot Image
# ============================================
echo ">>> Building Spring Boot Standard (Undertow)..."
echo ""

cd "$PROJECT_ROOT/spring-boot-simple-rest-api/com.divit.spring-boot-simple-rest-api"

docker build \
    -f "$SCRIPT_DIR/spring-boot-standard/Dockerfile" \
    -t spring-boot-standard:latest \
    -t spring-boot-standard:openj9 \
    .

echo ""
echo "✓ Spring Boot image built: spring-boot-standard:openj9"
echo ""

# ============================================
# Build Rust-Spring Image
# ============================================
echo ">>> Building Rust-Spring Performance (Hyper)..."
echo ""

cd "$PROJECT_ROOT/rust-spring-boot"

docker build \
    -f "$SCRIPT_DIR/rust-spring-perf/Dockerfile" \
    -t rust-spring-perf:latest \
    -t rust-spring-perf:openj9 \
    .

echo ""
echo "✓ Rust-Spring image built: rust-spring-perf:openj9"
echo ""

# ============================================
# Summary
# ============================================
echo "=================================================="
echo "  Build Complete!"
echo "=================================================="
echo ""
echo "Images:"
docker images | grep -E "spring-boot-standard|rust-spring-perf" | head -4
echo ""
echo "Run with:"
echo "  cd $SCRIPT_DIR && docker-compose up -d"
echo ""
