FROM ibm-semeru-runtimes:open-21-jdk-jammy AS builder

WORKDIR /build

ARG CORE_JAR=target/rust-java-rest-3.2.1-core-runtime.jar

COPY ${CORE_JAR} /build/framework.jar
COPY benchmark/minimal-production/src /build/src

RUN mkdir -p /build/classes && \
    javac -encoding UTF-8 \
      -proc:none \
      -cp /build/framework.jar \
      -d /build/classes \
      $(find /build/src -name '*.java') && \
    java -cp /build/classes:/build/framework.jar \
      com.reactor.rust.startup.StartupIndexGenerator \
      --output /build/classes \
      --packages com.reactor.benchmark.minimal \
      --exclude-websocket \
      --exclude-static-files

FROM ibm-semeru-runtimes:open-21-jre-jammy

WORKDIR /app

ENV MALLOC_ARENA_MAX=2 \
    MALLOC_TRIM_THRESHOLD_=131072 \
    JAVA_OPTS="-Xms8m -Xmx48m -Xss256k -Xquickstart -Xtune:virtualized -Xshareclasses:none -XX:ActiveProcessorCount=1 -XX:-TransparentHugePage -Dfile.encoding=UTF-8 -Djava.security.egd=file:/dev/./urandom"

COPY --from=builder /build/classes /app/classes
COPY --from=builder /build/framework.jar /app/framework.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -cp '/app/classes:/app/framework.jar' com.reactor.benchmark.minimal.MinimalProductionApplication"]
