# syntax=docker/dockerfile:1.7

ARG MAVEN_IMAGE=maven:3.9.9-eclipse-temurin-21
ARG SEMERU_IMAGE=icr.io/appcafe/ibm-semeru-runtimes:open-21-jdk-ubi9-minimal

FROM ${MAVEN_IMAGE} AS build
WORKDIR /workspace

COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests install

COPY sample/pom.xml ./sample/pom.xml
RUN mvn -q -DskipTests -f sample/pom.xml dependency:go-offline
COPY sample/src ./sample/src
RUN mvn -q -DskipTests -f sample/pom.xml package

FROM ${SEMERU_IMAGE} AS runtime
USER root
WORKDIR /app

COPY --from=build /workspace/sample/target/rust-java-rest-*-sample.jar /app/app.jar
COPY docker/instant-on/entrypoint.sh /opt/reactor/entrypoint.sh

RUN chmod +x /opt/reactor/entrypoint.sh \
    && mkdir -p /checkpoint /app \
    && java -version
RUN /usr/local/sbin/criu --version >/dev/null 2>&1 || true

ENV APP_MAIN=com.reactor.rust.example.ReactorRustHyperApplication
ENV APP_CP=/app/app.jar
ENV SERVER_PORT=8080
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8
ENV REACTOR_RUNTIME_PROFILE=fast-start
ENV REACTOR_RUST_JAVA_LOG_LEVEL=warn
ENV REACTOR_INSTANTON_CHECKPOINT_DIR=/checkpoint
ENV REACTOR_INSTANTON_RESTORE=false

EXPOSE 8080
ENTRYPOINT ["/opt/reactor/entrypoint.sh"]
