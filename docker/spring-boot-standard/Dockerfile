# Spring Boot Standard (Undertow) - OpenJ9 Ultra-Minimal
#
# Build: docker build -t spring-boot-std -f Dockerfile ../../spring-boot-simple-rest-api/com.divit.spring-boot-simple-rest-api
# Run:   docker run -p 8888:8888 --memory=100m spring-boot-std

# ============================================
# STAGE 1: Build (with full JDK)
# ============================================
FROM ibm-semeru-runtimes:open-21-jdk-ubuntu AS builder

WORKDIR /build

# Copy Maven wrapper and pom
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .

# Download dependencies (cached layer)
RUN ./mvnw dependency:go-offline -B

# Copy source and build
COPY src ./src
RUN ./mvnw package -DskipTests -B -q

# ============================================
# STAGE 2: Runtime (minimal JRE)
# ============================================
FROM ibm-semeru-runtimes:open-21-jre-ubuntu

WORKDIR /app

# Create non-root user
RUN groupadd -r appgroup && useradd -r -g appgroup appuser

# Copy Spring Boot fat JAR
COPY --from=builder /build/target/*.jar ./app.jar

# OpenJ9 Ultra-Minimal JVM Options:
# -Xquickstart: Fast startup (compiles less aggressively)
# -Xshareclasses: Shared class cache for faster restarts
# -Xtune:virtualized: Optimized for containers
# -Xscmx: Shared class cache size
ENV JAVA_OPTS="\
-Xms16m \
-Xmx48m \
-Xquickstart \
-Xshareclasses:name=spring-boot,cacheDir=/tmp,readOnly \
-Xscmx50m \
-Xtune:virtualized \
-XX:+UseCompressedOops \
-XX:+UseCompressedClassPointers \
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
