#!/bin/bash
# Performance Profiling Script
# Run Java profiling tests to identify bottlenecks

echo "╔═══════════════════════════════════════════════════════════════════════════════╗"
echo "║                    RUST-SPRING PROFILING SUITE                                ║"
echo "╚═══════════════════════════════════════════════════════════════════════════════╝"
echo ""

cd rust-spring-boot

# Compile profiling tests
echo "=== Compiling Profiling Tests ==="
javac -cp "target/rust-spring-1.0.0.jar:target/lib/*" \
    -d target/test-classes \
    ../test/profiling/ProfilingTests.java 2>&1

if [ $? -ne 0 ]; then
    echo "ERROR: Compilation failed"
    exit 1
fi

echo "✓ Compilation successful"
echo ""

# Run profiling tests
echo "=== Running Profiling Tests ==="
java -cp "target/test-classes:target/rust-spring-1.0.0.jar:target/lib/*" \
    -Xms64m -Xmx128m \
    com.reactor.rust.profiling.ProfilingTests

echo ""
echo "=== Profiling Complete ==="
