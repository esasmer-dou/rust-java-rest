# Rust-Java REST Sample Application

This directory is the runnable demo and benchmark application for `rust-java-rest`.

It is deliberately separate from the production framework JAR. The sample contains example
handlers, DTOs, WebSocket handlers, and benchmark routes. A normal application dependency does not
load or package these classes.

## Build And Run

Build and install the core library first:

```powershell
cd ..
mvn clean install
mvn -f sample/pom.xml clean package
java -jar sample/target/rust-java-rest-3.3.1-sample.jar
```

The application listens on the port configured by `server.port`.

## Which Artifact To Use

| Need | Artifact |
|------|----------|
| Build a production service | `com.reactor:rust-java-rest:3.3.1` |
| Run the examples locally | `sample/target/rust-java-rest-3.3.1-sample.jar` |
| Build a self-contained benchmark/container classpath | `target/rust-java-rest-3.3.1-core-runtime.jar` |

Do not add the sample JAR as a production dependency. Measure production RSS with your own
application classes or with `benchmark/minimal-production`, not with the demo route surface.
