#!/bin/bash
# Run Performance Profiling Tests
#
# Usage: bash test/run-profiling-tests.sh

echo "========================================================================"
echo "           RUST-SPRING PERFORMANCE PROFILING"
echo "========================================================================"
echo ""

cd rust-spring-boot

# Ensure project is compiled
echo ">>> Compiling project..."
mvn compile -q

if [ $? -ne 0 ]; then
    echo "ERROR: Maven compile failed"
    exit 1
fi

# Copy profiling test to source
mkdir -p target/profiling
cp ../test/profiling/ProfilingTests.java target/profiling/

# Compile profiling test
echo ">>> Compiling profiling tests..."
javac -cp "target/classes:target/lib/*" \
    -d target/profiling \
    target/profiling/ProfilingTests.java 2>&1

if [ $? -ne 0 ]; then
    echo "ERROR: Profiling test compilation failed"
    exit 1
fi

echo ">>> Running profiling tests..."
echo ""

# Run profiling tests
java -cp "target/profiling:target/classes:target/lib/*" \
    -Xms64m -Xmx128m \
    -XX:+UseCompressedOops \
    com.reactor.rust.profiling.ProfilingTests

echo ""
echo "========================================================================"
echo "  PROFILING COMPLETE"
echo "========================================================================"
