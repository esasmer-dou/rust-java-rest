# Rust-Spring Performance (Hyper + JNI) - Minimal Memory
#
# Build from project root:
#   docker build -t rust-spring-perf:minimal -f docker/rust-spring-perf/Dockerfile .
#
# Run:
#   docker run -p 8080:8080 --memory=40m rust-spring-perf:minimal

# ============================================
# STAGE 1: Build Rust Library
# ============================================
FROM rust:1.82-bookworm AS rust-builder

WORKDIR /rust-build

# Copy Rust source
COPY rust-spring/Cargo.toml rust-spring/Cargo.lock ./
COPY rust-spring/src ./src

# Build release library
RUN cargo build --release

# ============================================
# STAGE 2: Build Java (with full JDK)
# ============================================
FROM eclipse-temurin:21-jdk-jammy AS java-builder

WORKDIR /build

# Install Maven
RUN apt-get update && apt-get install -y --no-install-recommends \
    maven \
    && rm -rf /var/lib/apt/lists/*

# Copy pom first (cached layer)
COPY rust-spring-boot/pom.xml .

# Download dependencies
RUN mvn dependency:go-offline -B -q || true

# Copy source and build
COPY rust-spring-boot/src ./src
RUN mvn package -DskipTests -B -q

# Extract dependencies
RUN mkdir -p target/lib && \
    mvn dependency:copy-dependencies -DoutputDirectory=target/lib -B -q

# ============================================
# STAGE 3: Runtime (minimal Ubuntu JRE)
# ============================================
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Create non-root user
RUN groupadd -r appgroup && useradd -r -g appgroup appuser

# Install curl for healthcheck
RUN apt-get update && apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/* && \
    apt-get clean

# Copy JAR and dependencies
COPY --from=java-builder /build/target/rust-spring-1.0.0.jar ./app.jar
COPY --from=java-builder /build/target/lib ./lib/

# Copy native Rust library
COPY --from=rust-builder /rust-build/target/release/librust_hyper.so ./native/rust_hyper.so

# Ultra-Minimal JVM Options (HotSpot Serial GC - lowest memory)
ENV JAVA_OPTS="\
-Xms4m \
-Xmx20m \
-XX:+UseSerialGC \
-XX:MaxHeapFreeRatio=10 \
-XX:MinHeapFreeRatio=5 \
-XX:+UseCompressedOops \
-XX:+UseCompressedClassPointers \
-XX:MaxMetaspaceSize=24m \
-XX:CompressedClassSpaceSize=8m \
-XX:InitialCodeCacheSize=1m \
-XX:ReservedCodeCacheSize=8m \
-XX:+TieredCompilation \
-XX:TieredStopAtLevel=1 \
-Djava.security.egd=file:/dev/./urandom \
-Dfile.encoding=UTF-8"

ENV RUST_LIB_PATH=/app/native

# Create necessary directories
RUN mkdir -p /tmp && chown -R appuser:appgroup /app /tmp

USER appuser

EXPOSE 8080

# Health check
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -cp app.jar:lib/* com.reactor.rust.ReactorRustHyperApplication"]
