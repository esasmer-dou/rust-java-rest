# Spring Boot Standard (Undertow) - Minimal Memory
#
# Build: docker build -t spring-boot-std -f Dockerfile ../../spring-boot-simple-rest-api/com.divit.spring-boot-simple-rest-api
# Run:   docker run -p 8888:8888 --memory=100m spring-boot-std

# ============================================
# STAGE 1: Build (with full JDK + Maven)
# ============================================
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /build

# Install Maven
RUN apt-get update && apt-get install -y --no-install-recommends maven && \
    rm -rf /var/lib/apt/lists/*

# Copy pom first (cached layer)
COPY pom.xml .

# Download dependencies
RUN mvn dependency:go-offline -B -q || true

# Copy source and build
COPY src ./src
RUN mvn package -DskipTests -B -q

# ============================================
# STAGE 2: Runtime (minimal JRE)
# ============================================
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Create non-root user
RUN groupadd -r appgroup && useradd -r -g appgroup appuser

# Install curl for healthcheck
RUN apt-get update && apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

# Copy Spring Boot fat JAR
COPY --from=builder /build/target/*.jar ./app.jar

# HotSpot JVM Options (minimal memory)
ENV JAVA_OPTS="\
-Xms16m \
-Xmx64m \
-XX:+UseSerialGC \
-XX:MaxHeapFreeRatio=20 \
-XX:MinHeapFreeRatio=10 \
-XX:+UseCompressedOops \
-XX:+UseCompressedClassPointers \
-XX:MaxMetaspaceSize=64m \
-Djava.security.egd=file:/dev/./urandom \
-Dfile.encoding=UTF-8 \
-Dspring.jmx.enabled=false \
-Dspring.main.banner-mode=off"

# Create cache directory
RUN mkdir -p /tmp && chown -R appuser:appgroup /app /tmp

USER appuser

EXPOSE 8888

# Health check
HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=3 \
    CMD curl -f http://localhost:8888/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
