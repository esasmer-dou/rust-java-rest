#!/bin/bash
# Local Run Script for Rust-Spring Performance
# Builds Rust library and runs Java application

set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUST_DIR="$SCRIPT_DIR/../rust-spring"
JAVA_DIR="$SCRIPT_DIR/../rust-spring-boot"
NATIVE_DIR="$SCRIPT_DIR/../native"

echo "=== Rust-Spring Local Run ==="
echo ""
# Step 1: Build Rust library
echo "[1/5] Building Rust library..."
cd "$RUST_DIR"
cargo build --release
echo "      Rust build complete."

# Step 2: Copy native library to native directory
echo "[2/5] Copying native library..."
mkdir -p "$NATIVE_DIR"
LIB_FILE=""
if [ -f "$RUST_DIR/target/release/rust_hyper.dll" ]; then
    # Try to remove old file first (in case it's locked)
    rm -f "$NATIVE_DIR/rust_hyper.dll" 2>/dev/null || true
    # Copy with force
    cp -f "$RUST_DIR/target/release/rust_hyper.dll" "$NATIVE_DIR/" 2>/dev/null || {
        echo "      WARNING: Could not copy DLL (may be in use). Using build directory."
        LIB_FILE="$RUST_DIR/target/release/rust_hyper.dll"
    }
    if [ -z "$LIB_FILE" ]; then
        LIB_FILE="$NATIVE_DIR/rust_hyper.dll"
        echo "      Copied: rust_hyper.dll"
    fi
elif [ -f "$RUST_DIR/target/release/librust_hyper.so" ]; then
    cp -f "$RUST_DIR/target/release/librust_hyper.so" "$NATIVE_DIR/rust_hyper.so"
    LIB_FILE="$NATIVE_DIR/rust_hyper.so"
    echo "      Copied: rust_hyper.so"
else
    echo "ERROR: Native library not found!"
    exit 1
fi

# Step 3: Kill any process using port 8080
echo "[3/5] Checking port 8080..."
if command -v netstat &> /dev/null; then
    if [[ "$OSTYPE" == "msys" || "$OSTYPE" == "win32" ]]; then
        # Windows - find and kill process on port 8080
        PID=$(netstat -ano 2>/dev/null | grep ":8080.*LISTENING" | awk '{print $5}' | head -1)
        if [ -n "$PID" ]; then
            echo "      Killing process $PID on port 8080..."
            taskkill //F //PID "$PID" 2>/dev/null || true
            sleep 1
        else
            echo "      Port 8080 is free."
        fi
    else
        # Linux/Mac - find and kill process on port 8080
        PID=$(lsof -ti:8080 2>/dev/null || true)
        if [ -n "$PID" ]; then
            echo "      Killing process $PID on port 8080..."
            kill -9 "$PID" 2>/dev/null || true
            sleep 1
        else
            echo "      Port 8080 is free."
        fi
    fi
fi

# Step 4: Build Java application
echo "[4/5] Building Java application..."
cd "$JAVA_DIR"
mvn package -DskipTests -q

# Find the JAR file
JAR_FILE=$(find target -name "rust-spring-*.jar" -type f | grep -v "\.original" | head -1)
if [ -z "$JAR_FILE" ]; then
    echo "ERROR: JAR file not found!"
    exit 1
fi
echo "      Built: $(basename $JAR_FILE)"

# Step 5: Run with native library path and JVM parameters
echo "[5/5] Starting application..."
echo ""
echo "=== Starting Application ==="
echo "Native library: $LIB_FILE"
echo "JAR file: $JAR_FILE"
echo ""

# JVM Parameters for Constraint #1: Memory < 50 MB
# - Xmx32m: Max heap 32MB (leaving room for native + metaspace)
# - Xms8m: Initial heap 8MB
# - G1GC: Low latency garbage collector
# - MaxGCPauseMillis=2: Sub-millisecond GC pauses target
# - UseStringDeduplication: Reduce string memory overhead
# - MaxDirectMemorySize=8m: Limit direct buffer memory
# - CompressedClassSpaceSize=16m: Reduce metaspace usage
# - ExitOnOutOfMemoryError: Fail fast on memory issues

JVM_OPTS="-Xmx32m -Xms8m -XX:+UseG1GC -XX:MaxGCPauseMillis=2 -XX:+UseStringDeduplication -XX:MaxDirectMemorySize=8m -XX:CompressedClassSpaceSize=16m -XX:+ExitOnOutOfMemoryError"

echo "JVM Options: $JVM_OPTS"
echo ""

# Set library path and run JAR directly
export RUST_LIB_PATH="$NATIVE_DIR"
java $JVM_OPTS \
    -Djava.library.path="$NATIVE_DIR" \
    -jar "$JAR_FILE"
