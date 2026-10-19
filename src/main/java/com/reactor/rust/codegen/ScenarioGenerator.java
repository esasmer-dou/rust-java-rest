package com.reactor.rust.codegen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

/** Generates two runnable processes, keeping business contracts separate from transport. */
public final class ScenarioGenerator {
    private ScenarioGenerator() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !(args[0].equals("cache") || args[0].equals("dubbo")))
            throw new IllegalArgumentException("Usage: ScenarioGenerator cache|dubbo <new-directory>");
        generate(args[0], Path.of(args[1]));
    }

    public static void generate(String kind, Path destination) throws Exception {
        if (!kind.equals("cache") && !kind.equals("dubbo")) throw new IllegalArgumentException("Unknown scenario");
        // Unlike the single-project generator, require a new directory, including on partial retries.
        Files.createDirectory(destination.toAbsolutePath());
        Path root = destination.toAbsolutePath();
        boolean cache = kind.equals("cache");
        String front = cache ? "reader" : "consumer";
        String back = cache ? "writer" : "provider";
        ProjectGenerator.generate(new ProjectGenerator.Options(root.resolve(front), "com.example", front,
                "com.example.app", cache ? ProjectGenerator.Mode.CACHE_READER : ProjectGenerator.Mode.DUBBO_STATIC, 8080));
        if (cache) {
            ProjectGenerator.generate(new ProjectGenerator.Options(root.resolve(back), "com.example", back,
                    "com.example.writer", ProjectGenerator.Mode.CACHE_WRITER, 8080));
            put(root, front + "/src/main/java/com/example/app/SnapshotHandler.java", """
                    package com.example.app;
                    import com.reactor.rust.annotations.*;
                    import com.reactor.rust.cache.core.RustCache;
                    import com.reactor.rust.cache.versioned.VersionedJsonCacheReader;
                    import com.reactor.rust.cache.http.CacheResponses;
                    import com.reactor.rust.http.*;
                    @RestController("/api/v1")
                    public final class SnapshotHandler {
                        private final VersionedJsonCacheReader reader;
                        public SnapshotHandler(RustCache cache) {
                            reader = cache.versionedJsonReader("app.snapshot.snapshot", 1000);
                        }
                        @GetMapping("/snapshot")
                        public ResponseEntity<RawResponse> get() { return CacheResponses.problem(reader.getMeta()); }
                    }
                    """);
        } else {
            // The generated consumer and provider import the exact same wire interface.
            Path contract = root.resolve(front + "/src/main/java/com/example/app/EchoService.java");
            put(root, "contract/src/main/java/com/example/app/EchoService.java", Files.readString(contract));
            Files.delete(contract);
            put(root, "contract/pom.xml", """
                    <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId><artifactId>contract</artifactId><version>0.1.0-SNAPSHOT</version>
                    <properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                    <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin></plugins></build>
                    </project>
                    """);
            Path consumerPom = root.resolve(front + "/pom.xml");
            Files.writeString(consumerPom, Files.readString(consumerPom).replace("<dependencies>", "<dependencies>" + dep("com.example", "contract", "0.1.0-SNAPSHOT")));
            put(root, back + "/pom.xml", pom(back,
                    dep("com.example", "contract", "0.1.0-SNAPSHOT")
                    + dep("com.reactor", "java-rust-dubbo", "0.8.0")
                    + dep("org.apache.dubbo", "dubbo-rpc-dubbo", "3.3.5", "org.apache.dubbo:dubbo-cluster", "org.apache.dubbo:dubbo-native")
                    + dep("org.apache.dubbo", "dubbo-remoting-netty4", "3.3.5", "io.netty:netty-handler-proxy",
                            "io.netty:netty-codec-http2", "io.netty:netty-transport-classes-epoll", "io.netty:netty-transport-native-epoll", "org.apache.dubbo:dubbo-native")
                    + dep("org.apache.dubbo", "dubbo-serialization-hessian2", "3.3.5", "org.apache.dubbo:dubbo-native")
                    + dep("io.netty", "netty-handler", "4.2.1.Final")
                    + dep("org.slf4j", "slf4j-nop", "1.7.36")));
            put(root, back + "/src/main/java/com/example/app/Application.java", """
                    package com.example.app;
                    import com.reactor.rust.dubbo.provider.DubboProviderApplication;
                    import java.nio.charset.StandardCharsets;
                    public final class Application {
                        public static void main(String[] args) throws Exception {
                            DubboProviderApplication.run("application.properties", "example",
                                context -> context.service(EchoService.class, new EchoProvider()));
                        }
                        public static final class EchoProvider implements EchoService {
                            public byte[] echo() { return "{\\\"status\\\":\\\"ready\\\"}".getBytes(StandardCharsets.UTF_8); }
                        }
                    }
                    """);
            put(root, back + "/src/main/resources/application.properties", """
                    dubbo.provider.application-name=example-provider
                    dubbo.provider.host=provider
                    dubbo.provider.bind-host=0.0.0.0
                    dubbo.provider.port=20880
                    reactor.dubbo.registry-enabled=false
                    dubbo.provider.service.default.max-concurrent=4
                    dubbo.provider.executor.max-threads=4
                    dubbo.provider.executor.queue-capacity=16
                    dubbo.provider.executor.io-threads=1
                    """);
        }
        put(root, "pom.xml", """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId><artifactId>scenario</artifactId><version>0.1.0-SNAPSHOT</version><packaging>pom</packaging>
                <modules>%s<module>%s</module><module>%s</module></modules></project>
                """.formatted(cache ? "" : "<module>contract</module>", back, front));
        for (String app : new String[]{front, back}) {
            put(root, app + "/Dockerfile", """
                    FROM ibm-semeru-runtimes:open-21-jre
                    WORKDIR /app
                    COPY target/*.jar /app/
                    COPY target/dependency/ /app/lib/
                    ENV JAVA_TOOL_OPTIONS="-Xms20m -Xmx64m -Xss256k -XX:ActiveProcessorCount=1 -Xgc:threads=1"
                    ENTRYPOINT ["java","-cp","/app/*:/app/lib/*","%s.Application"]
                    """.formatted(cache && app.equals(back) ? "com.example.writer" : "com.example.app"));
        }
        String infrastructure = cache ? """
                  redis:
                    image: redis:7.4-alpine
                    mem_limit: 96m
                    command: ["redis-server", "--save", "", "--appendonly", "no"]
                """ : "";
        put(root, "compose.yaml", "services:\n" + infrastructure + """
                  %s:
                    build: ./%s
                    mem_limit: 192m
                    cpus: 1
                %s
                  %s:
                    build: ./%s
                    mem_limit: 128m
                    cpus: 1
                    ports: ["127.0.0.1:8080:8080"]
                    environment:
                      %s
                """.formatted(back, back, cache ? "    environment:\n      REACTOR_CACHE_REDIS_HOST: redis" : "",
                front, front, cache ? "REACTOR_CACHE_REDIS_HOST: redis" : "REACTOR_DUBBO_PROVIDERS: provider:20880"));
        String endpoint = cache ? "/api/v1/snapshot" : "/api/v1/dubbo/echo";
        put(root, "smoke.ps1", """
                $ErrorActionPreference='Stop'
                $deadline=(Get-Date).AddSeconds(60)
                do {
                    try {
                        $body=Invoke-RestMethod 'http://127.0.0.1:8080%s' -TimeoutSec 2
                        if($body.status -eq 'ready') { Write-Output 'PASS'; exit 0 }
                    } catch {}
                    Start-Sleep -Milliseconds 250
                } while((Get-Date) -lt $deadline)
                throw 'Scenario did not become ready in 60 seconds'
                """.formatted(endpoint));
        String commands = """
                ```powershell
                mvn -B -ntp clean install dependency:copy-dependencies -DincludeScope=runtime
                docker compose up --build -d
                ./smoke.ps1
                docker compose down
                ```
                """;
        put(root, "README.md", "# " + kind + " scenario\n\n[English](README.md) | [Turkish](README.tr.md)\n\n"
                + "Two independent processes. The first response is `{\"status\":\"ready\"}`.\n\n"
                + "Configure Maven read:packages credentials first. No credentials are generated.\n\n" + commands
                + "\nThe fixture is deliberately small. Replace its business data; do not use demo data as a performance claim. "
                + "Readiness is polled, not a fixed startup sleep. All retries in smoke are GET requests.\n");
        put(root, "README.tr.md", "# " + kind + " örneği\n\n[English](README.md) | [Turkish](README.tr.md)\n\n"
                + "İki ayrı uygulama çalışır. İlk yanıt `{\"status\":\"ready\"}` olur.\n\n"
                + "Önce Maven settings.xml içinde read:packages erişimini ayarlayın. Bu araç parola üretmez.\n\n" + commands
                + "\nÖrnek veri küçüktür. Kendi iş verinizle değiştirin. Bu veri bir performans iddiası değildir. "
                + "Kontrol betiği hazır olana kadar yalnız GET isteğini tekrarlar.\n");
    }

    private static String dep(String group, String artifact, String version) {
        return "<dependency><groupId>" + group + "</groupId><artifactId>" + artifact + "</artifactId><version>" + version + "</version></dependency>";
    }
    private static String dep(String group, String artifact, String version, String... exclusions) {
        var xml = new StringBuilder("<exclusions>");
        for (String coordinate : exclusions) {
            String[] parts = coordinate.split(":", 2);
            xml.append("<exclusion><groupId>").append(parts[0]).append("</groupId><artifactId>")
                    .append(parts[1]).append("</artifactId></exclusion>");
        }
        return dep(group, artifact, version).replace("</dependency>", xml.append("</exclusions></dependency>").toString());
    }
    private static String pom(String artifact, String dependencies) {
        return "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>"
                + "<parent><groupId>com.reactor</groupId><artifactId>rust-java-platform-parent</artifactId><version>4.7.0</version><relativePath/></parent>"
                + "<groupId>com.example</groupId><artifactId>" + artifact + "</artifactId><version>0.1.0-SNAPSHOT</version>"
                + "<dependencies>" + dependencies + "</dependencies></project>";
    }
    private static void put(Path root, String file, String content) throws Exception {
        Path path = root.resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
