# syntax=docker/dockerfile:1

FROM ibm-semeru-runtimes:open-21-jdk-jammy AS builder

WORKDIR /build

ARG CORE_JAR=target/rust-java-rest-*-core-runtime.jar
ARG CODEGEN_JAR=target/rust-java-rest-*-codegen.jar
ARG JAVA_MODULES=java.base,java.logging,java.management,java.sql,jdk.charsets,jdk.crypto.ec,jdk.unsupported

RUN apt-get update \
    && apt-get install -y --no-install-recommends binutils \
    && rm -rf /var/lib/apt/lists/*

COPY ${CORE_JAR} /build/framework.jar
COPY ${CODEGEN_JAR} /build/codegen.jar
COPY benchmark/minimal-production/src /build/src
COPY benchmark/probes/src/JlinkRuntimeSmoke.java /build/probes/JlinkRuntimeSmoke.java

RUN mkdir -p /build/classes && \
    javac -encoding UTF-8 \
      -cp /build/framework.jar \
      -processorpath /build/codegen.jar:/build/framework.jar \
      -processor com.reactor.rust.codegen.ReactorStartupProcessor \
      -d /build/classes \
      $(find /build/src -name '*.java') && \
    mkdir -p /build/probe-classes && \
    javac -encoding UTF-8 \
      -proc:none \
      -cp /build/framework.jar \
      -d /build/probe-classes \
      /build/probes/JlinkRuntimeSmoke.java

ARG JLINK_COMPRESS=zip-0

RUN jlink \
    --add-modules "${JAVA_MODULES}" \
    --strip-debug \
    --no-header-files \
    --no-man-pages \
    --compress="${JLINK_COMPRESS}" \
    --output /opt/reactor-jre

RUN JAVA_TOOL_OPTIONS="" /opt/reactor-jre/bin/java \
      -cp /build/probe-classes:/build/framework.jar \
      JlinkRuntimeSmoke

FROM ubuntu:22.04

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates libstdc++6 zlib1g \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app

ENV JAVA_HOME=/opt/reactor-jre \
    PATH=/opt/reactor-jre/bin:$PATH \
    JAVA_TOOL_OPTIONS="" \
    HOME=/app \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    MALLOC_ARENA_MAX=2 \
    MALLOC_TRIM_THRESHOLD_=131072 \
    JAVA_OPTS="-Xms8m -Xmx48m -Xss256k -Xquickstart -Xtune:virtualized -Xshareclasses:none -XX:ActiveProcessorCount=1 -XX:-TransparentHugePage -Dfile.encoding=UTF-8 -Djava.security.egd=file:/dev/./urandom"

WORKDIR /app

RUN mkdir -p /app/.reactor/native \
    && chown -R app:app /app

COPY --from=builder /opt/reactor-jre /opt/reactor-jre
COPY --from=builder /build/classes /app/classes
COPY --from=builder /build/framework.jar /app/framework.jar

USER app

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -cp '/app/classes:/app/framework.jar' com.reactor.benchmark.minimal.MinimalProductionApplication"]
