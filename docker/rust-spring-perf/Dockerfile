# Rust-Spring Performance (Hyper + JNI) - OpenJ9 Ultra-Minimal
#
# Build from project root:
#   docker build -t rust-spring-perf -f docker/rust-spring-perf/Dockerfile .
#
# Run:
#   docker run -p 8080:8080 --memory=50m rust-spring-perf

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
FROM ibm-semeru-runtimes:open-21-jdk-jammy AS java-builder

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
# STAGE 3: Runtime (minimal JRE)
# ============================================
FROM ibm-semeru-runtimes:open-21-jre-jammy

WORKDIR /app

# Create non-root user
RUN groupadd -r appgroup && useradd -r -g appgroup appuser

# Install curl for healthcheck
RUN apt-get update && apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

# Copy JAR and dependencies
COPY --from=java-builder /build/target/rust-spring-1.0.0.jar ./app.jar
COPY --from=java-builder /build/target/lib ./lib/

# Copy native Rust library - rename to expected filename
COPY --from=rust-builder /rust-build/target/release/librust_hyper.so ./native/rust_hyper.so

# OpenJ9 Ultra-Minimal JVM Options
ENV JAVA_OPTS="\
-Xms8m \
-Xmx32m \
-Xquickstart \
-Xshareclasses:name=rust-spring,cacheDir=/tmp \
-Xscmx50m \
-Xtune:virtualized \
-XX:+UseCompressedOops \
-XX:+UseCompressedClassPointers \
-Djava.security.egd=file:/dev/./urandom \
-Dfile.encoding=UTF-8 \
--enable-native-access=ALL-UNNAMED"

ENV RUST_LIB_PATH=/app/native

# Create cache directory
RUN mkdir -p /tmp && chown -R appuser:appgroup /app /tmp

USER appuser

EXPOSE 8080

# Health check
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -cp app.jar:lib/* com.reactor.rust.ReactorRustHyperApplication"]
