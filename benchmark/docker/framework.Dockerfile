FROM ibm-semeru-runtimes:open-21-jre-jammy

WORKDIR /app

ARG JAR_FILE=sample/target/rust-java-rest-*-sample.jar

ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    MALLOC_ARENA_MAX=2 \
    MALLOC_TRIM_THRESHOLD_=131072 \
    JAVA_OPTS="-Xms8m -Xmx48m -Xss256k -Xquickstart -Xtune:virtualized -Xshareclasses:none -XX:ActiveProcessorCount=1 -XX:-TransparentHugePage -Dfile.encoding=UTF-8 -Djava.security.egd=file:/dev/./urandom"

COPY ${JAR_FILE} /app/app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
