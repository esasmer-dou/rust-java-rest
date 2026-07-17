package com.reactor.rust.codegen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.lang.model.SourceVersion;

/** Small, dependency-free project generator for the supported production shapes. */
public final class ProjectGenerator {

    private static final String REST_VERSION = "4.0.0";
    private static final String CACHE_VERSION = "0.5.0";
    private static final String DUBBO_VERSION = "0.5.0";
    private static final String ZOOKEEPER_VERSION = "3.7.2";
    private static final Pattern MAVEN_ID = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern JAVA_PACKAGE = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    private ProjectGenerator() {}

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        generate(options);
        System.out.println("Generated " + options.mode().id + " project at "
                + options.output().toAbsolutePath());
    }

    static void generate(Options options) throws IOException {
        Path output = options.output().toAbsolutePath().normalize();
        if (directoryNotEmpty(output)) {
            throw new IllegalArgumentException("Output directory must be empty: " + output);
        }
        Files.createDirectories(output);
        write(output.resolve("pom.xml"), pom(options));
        Path javaRoot = output.resolve("src/main/java")
                .resolve(options.packageName().replace('.', '/'));
        Path resources = output.resolve("src/main/resources");
        Files.createDirectories(javaRoot);
        Files.createDirectories(resources);
        switch (options.mode()) {
            case REST -> generateRest(options, javaRoot, resources);
            case CACHE_READER -> generateCacheReader(options, javaRoot, resources);
            case CACHE_WRITER -> generateCacheWriter(options, javaRoot, resources);
            case DUBBO_STATIC, DUBBO_ZOOKEEPER -> generateDubbo(options, javaRoot, resources);
        }
    }

    private static void generateRest(Options options, Path javaRoot, Path resources) throws IOException {
        write(javaRoot.resolve("Application.java"), """
                package %s;

                import com.reactor.rust.app.RestApplication;

                public final class Application {
                    private Application() {}
                    public static void main(String[] args) {
                        RestApplication.run(context -> context.handlers(new HelloHandler()));
                    }
                }
                """.formatted(options.packageName()));
        write(javaRoot.resolve("HelloHandler.java"), """
                package %s;

                import com.reactor.rust.annotations.GetMapping;
                import com.reactor.rust.http.JsonResponses;
                import com.reactor.rust.http.RawResponse;
                import com.reactor.rust.http.ResponseEntity;

                public final class HelloHandler {
                    @GetMapping(value = "/api/v1/hello", responseType = RawResponse.class)
                    public ResponseEntity<RawResponse> hello() {
                        return ResponseEntity.ok(JsonResponses.stringField("message", "hello"));
                    }
                }
                """.formatted(options.packageName()));
        write(resources.resolve("rust-spring.properties"), restProperties(options.port()));
        writeReadme(options);
    }

    private static void generateCacheReader(Options options, Path javaRoot, Path resources) throws IOException {
        write(javaRoot.resolve("Application.java"), """
                package %s;

                import com.reactor.rust.app.RestApplication;
                import com.reactor.rust.cache.config.CacheProperties;
                import com.reactor.rust.cache.core.RustCaches;
                import com.reactor.rust.health.HealthStarter;

                public final class Application {
                    private Application() {}
                    public static void main(String[] args) {
                        RestApplication.run(context -> {
                            CacheProperties properties = CacheProperties.from(context.properties());
                            var cache = context.manage(RustCaches.create(properties.asProperties()));
                            context.handlers(
                                    HealthStarter.application("%s").build(),
                                    new CacheHandler(cache));
                        });
                    }
                }
                """.formatted(options.packageName(), options.artifactId()));
        write(javaRoot.resolve("CacheHandler.java"), """
                package %s;

                import com.reactor.rust.annotations.GetMapping;
                import com.reactor.rust.cache.core.RustCache;
                import com.reactor.rust.http.MediaType;
                import com.reactor.rust.http.RawResponse;
                import com.reactor.rust.http.ResponseEntity;

                public final class CacheHandler {
                    private final RustCache cache;
                    public CacheHandler(RustCache cache) { this.cache = cache; }

                    @GetMapping(value = "/api/v1/cache/metrics", responseType = RawResponse.class)
                    public ResponseEntity<RawResponse> metrics() {
                        return ResponseEntity.ok(RawResponse.text(
                                cache.metricsJson(), MediaType.APPLICATION_JSON_UTF8));
                    }
                }
                """.formatted(options.packageName()));
        write(resources.resolve("rust-spring.properties"), restProperties(options.port()) + """
                reactor.cache.redis.topology=standalone
                reactor.cache.redis.access-mode=read-only
                reactor.cache.redis.host=127.0.0.1
                reactor.cache.redis.port=6379
                reactor.cache.redis.read-connections=2
                reactor.cache.redis.write-connections=1
                """);
        writeReadme(options);
    }

    private static void generateCacheWriter(Options options, Path javaRoot, Path resources) throws IOException {
        write(javaRoot.resolve("Application.java"), """
                package %s;

                import com.reactor.rust.cache.scheduler.ProjectionWriterApplication;

                public final class Application {
                    private Application() {}
                    public static void main(String[] args) {
                        ProjectionWriterApplication.runCache(
                                "application.properties", "app.writer", SnapshotWriter::create);
                    }
                }
                """.formatted(options.packageName()));
        write(javaRoot.resolve("SnapshotWriter.java"), """
                package %s;

                import com.reactor.rust.cache.config.CacheProperties;
                import com.reactor.rust.cache.core.RustCache;
                import com.reactor.rust.cache.projection.CacheWriterProjectionSettings;
                import com.reactor.rust.cache.projection.VersionedJsonProjectionMaterializer;
                import com.reactor.rust.cache.scheduler.ProjectionWriterApplication;
                import java.nio.charset.StandardCharsets;

                public final class SnapshotWriter {
                    private SnapshotWriter() {}

                    public static com.reactor.rust.cache.scheduler.ProjectionRefreshScheduler.ProjectionRefresher create(
                            ProjectionWriterApplication.ModuleContext context,
                            RustCache cache,
                            CacheProperties properties) {
                        var settings = CacheWriterProjectionSettings.resolveAll(properties, "app.writer");
                        VersionedJsonProjectionMaterializer materializer =
                                VersionedJsonProjectionMaterializer.builder(cache, settings, 128)
                                        .projection("snapshot", target -> target.writer().refreshSnapshot(
                                target.ttlMillis(), snapshot -> snapshot.putMeta(
                                        "{\\\"status\\\":\\\"ready\\\"}".getBytes(StandardCharsets.UTF_8))))
                                        .build();
                        return materializer::refresh;
                    }
                }
                """.formatted(options.packageName()));
        write(resources.resolve("application.properties"), """
                app.writer.projections=snapshot
                app.writer.namespace=app.snapshot
                app.writer.cache-ttl-ms=90000
                app.writer.initial-delay-ms=0
                app.writer.interval-ms=60000
                app.writer.lock-name=app.snapshot.writer
                app.writer.lock-ttl-ms=30000
                app.writer.cache-ttl-safety-margin-ms=30000
                app.writer.scheduler-threads=1
                app.writer.scheduler-thread-stack-bytes=262144
                app.writer.first-run-timeout-ms=60000
                app.writer.thread-name-prefix=cache-writer
                app.writer.shutdown-thread-name=cache-writer-shutdown
                app.writer.run-once=false
                reactor.cache.redis.topology=standalone
                reactor.cache.redis.access-mode=read-write
                reactor.cache.redis.host=127.0.0.1
                reactor.cache.redis.port=6379
                reactor.cache.redis.read-connections=1
                reactor.cache.redis.write-connections=1
                """);
        writeReadme(options);
    }

    private static void generateDubbo(Options options, Path javaRoot, Path resources) throws IOException {
        write(javaRoot.resolve("EchoService.java"), """
                package %s;
                public interface EchoService { byte[] echo(); }
                """.formatted(options.packageName()));
        write(javaRoot.resolve("EchoClientDefinition.java"), """
                package %s;
                import com.reactor.rust.dubbo.codegen.GenerateNativeDubboClient;
                @GenerateNativeDubboClient(service = EchoService.class, generatedName = "EchoClient")
                final class EchoClientDefinition { private EchoClientDefinition() {} }
                """.formatted(options.packageName()));
        write(javaRoot.resolve("Application.java"), """
                package %s;

                import com.reactor.rust.app.RestApplication;
                import com.reactor.rust.config.PropertiesLoader;
                import com.reactor.rust.dubbo.NativeDubboConsumers;
                import com.reactor.rust.dubbo.support.DubboConsumerSupport;

                public final class Application {
                    private Application() {}
                    public static void main(String[] args) {
                        RestApplication.run(context -> {
                            DubboConsumerSupport support = DubboConsumerSupport
                                    .fromProperties(PropertiesLoader.getAll())
                                    .discoveryProperty("app.dubbo.discovery");
                            var transport = context.manage(NativeDubboConsumers.create(support.config()));
                            context.handlers(new EchoHandler(EchoClient.create(transport, support)));
                        });
                    }
                }
                """.formatted(options.packageName()));
        write(javaRoot.resolve("EchoHandler.java"), """
                package %s;

                import com.reactor.rust.annotations.GetMapping;
                import com.reactor.rust.http.HttpStatus;
                import com.reactor.rust.http.RawResponse;
                import com.reactor.rust.http.ResponseEntity;
                import java.nio.charset.StandardCharsets;
                import java.util.concurrent.CompletableFuture;

                public final class EchoHandler {
                    private final EchoClient client;
                    public EchoHandler(EchoClient client) { this.client = client; }

                    @GetMapping(value = "/api/v1/dubbo/echo", responseType = RawResponse.class)
                    public CompletableFuture<ResponseEntity<RawResponse>> echo() {
                        return client.echoNativeJsonAsync()
                                .thenApply(handle -> ResponseEntity.ok(
                                        RawResponse.nativeResponse(handle.nativeId())))
                                .exceptionally(error -> ResponseEntity.status(
                                        HttpStatus.SERVICE_UNAVAILABLE,
                                        RawResponse.json(
                                                "{\\\"error\\\":\\\"dubbo_unavailable\\\"}"
                                                        .getBytes(StandardCharsets.UTF_8))));
                    }
                }
                """.formatted(options.packageName()));
        String discovery = options.mode() == Mode.DUBBO_ZOOKEEPER ? "zookeeper" : "static";
        String provider = options.mode() == Mode.DUBBO_ZOOKEEPER
                ? "reactor.dubbo.registry-address=zookeeper://127.0.0.1:2181\n"
                : "reactor.dubbo.providers=127.0.0.1:20880\n";
        write(resources.resolve("rust-spring.properties"), restProperties(options.port()) + """
                reactor.runtime.profile=micro-dubbo
                reactor.dubbo.enabled=true
                reactor.dubbo.transport=native
                app.dubbo.discovery=%s
                %s""".formatted(discovery, provider));
        writeReadme(options);
    }

    private static String pom(Options options) {
        boolean rest = options.mode() != Mode.CACHE_WRITER;
        boolean cache = options.mode() == Mode.CACHE_READER || options.mode() == Mode.CACHE_WRITER;
        boolean dubbo = options.mode() == Mode.DUBBO_STATIC || options.mode() == Mode.DUBBO_ZOOKEEPER;
        StringBuilder dependencies = new StringBuilder();
        if (rest) dependencies.append(dependency("com.reactor", "rust-java-rest", REST_VERSION, null, null));
        if (cache) dependencies.append(dependency("com.reactor", "java-rust-cache", CACHE_VERSION, null, null));
        if (dubbo) {
            String classifier = options.mode() == Mode.DUBBO_STATIC ? "native-static" : null;
            dependencies.append(dependency("com.reactor", "java-rust-dubbo", DUBBO_VERSION, classifier, null));
            dependencies.append(dependency("com.reactor", "java-rust-dubbo", DUBBO_VERSION, "codegen", "provided"));
            if (options.mode() == Mode.DUBBO_ZOOKEEPER) {
                dependencies.append(dependency(
                        "org.apache.zookeeper", "zookeeper", ZOOKEEPER_VERSION, null, null));
            }
        }
        StringBuilder processorPaths = new StringBuilder();
        StringBuilder processors = new StringBuilder();
        if (rest) {
            processorPaths.append(path("com.reactor", "rust-java-rest", REST_VERSION, "codegen"));
            processors.append("<annotationProcessor>com.reactor.rust.codegen.ReactorStartupProcessor</annotationProcessor>");
        }
        if (dubbo) {
            processorPaths.append(path("com.reactor", "java-rust-dubbo", DUBBO_VERSION, "codegen"));
            processors.append("<annotationProcessor>com.reactor.rust.dubbo.codegen.NativeDubboClientProcessor</annotationProcessor>");
        }
        String compilerPlugin = processors.isEmpty() ? "" : """
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.13.0</version>
                    <configuration>
                      <release>21</release>
                      <annotationProcessorPaths>%s</annotationProcessorPaths>
                      <annotationProcessors>%s</annotationProcessors>
                    </configuration>
                  </plugin>
                """.formatted(processorPaths, processors);
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>0.1.0-SNAPSHOT</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                  <dependencies>%s</dependencies>
                  <repositories>
                    <repository><id>github-rest</id><url>https://maven.pkg.github.com/esasmer-dou/rust-java-rest</url></repository>
                    <repository><id>github-cache</id><url>https://maven.pkg.github.com/esasmer-dou/java-rust-cache</url></repository>
                    <repository><id>github-dubbo</id><url>https://maven.pkg.github.com/esasmer-dou/java-rust-dubbo</url></repository>
                  </repositories>
                  <build><plugins>
                    %s
                    <plugin>
                      <groupId>org.codehaus.mojo</groupId>
                      <artifactId>exec-maven-plugin</artifactId>
                      <version>3.5.0</version>
                      <configuration><mainClass>%s.Application</mainClass></configuration>
                    </plugin>
                  </plugins></build>
                </project>
                """.formatted(
                options.groupId(), options.artifactId(), dependencies,
                compilerPlugin, options.packageName());
    }

    private static String dependency(String group, String artifact, String version, String classifier, String scope) {
        return "<dependency><groupId>" + group + "</groupId><artifactId>" + artifact
                + "</artifactId><version>" + version + "</version>"
                + (classifier == null ? "" : "<classifier>" + classifier + "</classifier>")
                + (scope == null ? "" : "<scope>" + scope + "</scope>") + "</dependency>";
    }

    private static String path(String group, String artifact, String version, String classifier) {
        return "<path><groupId>" + group + "</groupId><artifactId>" + artifact
                + "</artifactId><version>" + version + "</version><classifier>" + classifier
                + "</classifier></path>";
    }

    private static String restProperties(int port) {
        return "server.port=" + port + "\nserver.host=0.0.0.0\nreactor.runtime.profile=micro-rest\n";
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static boolean directoryNotEmpty(Path path) throws IOException {
        if (!Files.exists(path)) {
            return false;
        }
        if (!Files.isDirectory(path)) {
            return true;
        }
        try (Stream<Path> entries = Files.list(path)) {
            return entries.findAny().isPresent();
        }
    }

    private static void writeReadme(Options options) throws IOException {
        write(options.output().toAbsolutePath().normalize().resolve("README.md"), """
                # %s

                Generated Reactor project shape: `%s`.

                ## Run

                1. Configure the values in `src/main/resources/%s`.
                2. Ensure the native library bundled by the selected Reactor dependency matches the host.
                3. Run `mvn clean package`.
                4. Run `mvn exec:java`.

                GitHub Packages requires a Maven `settings.xml` entry with a token that has
                `read:packages` access.
                """.formatted(
                options.artifactId(),
                options.mode().id,
                options.mode() == Mode.CACHE_WRITER ? "application.properties" : "rust-spring.properties"));
    }

    enum Mode {
        REST("rest"),
        CACHE_READER("cache-reader"),
        CACHE_WRITER("cache-writer"),
        DUBBO_STATIC("dubbo-static"),
        DUBBO_ZOOKEEPER("dubbo-zookeeper");

        private final String id;

        Mode(String id) { this.id = id; }

        static Mode parse(String value) {
            for (Mode mode : values()) if (mode.id.equals(value)) return mode;
            throw new IllegalArgumentException("Unknown mode: " + value);
        }
    }

    record Options(Path output, String groupId, String artifactId, String packageName, Mode mode, int port) {

        static Options parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int index = 0; index < args.length; index += 2) {
                if (index + 1 >= args.length || !args[index].startsWith("--")) {
                    throw new IllegalArgumentException("Expected --key value arguments");
                }
                values.put(args[index].substring(2), args[index + 1]);
            }
            Path output = Path.of(required(values, "output"));
            String group = values.getOrDefault("group", "com.example");
            String artifact = required(values, "artifact");
            String packageName = values.getOrDefault(
                    "package", defaultPackageName(group, artifact));
            validateIdentifier(group, "group");
            validateIdentifier(artifact, "artifact");
            if (!JAVA_PACKAGE.matcher(packageName).matches() || !SourceVersion.isName(packageName)) {
                throw new IllegalArgumentException("--package is not a valid Java package: " + packageName);
            }
            int port = Integer.parseInt(values.getOrDefault("port", "8080"));
            if (port < 1 || port > 65535) throw new IllegalArgumentException("port is out of range");
            return new Options(output, group, artifact, packageName,
                    Mode.parse(values.getOrDefault("mode", "rest").toLowerCase(Locale.ROOT)), port);
        }

        private static String required(Map<String, String> values, String key) {
            String value = values.get(key);
            if (value == null || value.isBlank()) throw new IllegalArgumentException("--" + key + " is required");
            return value.trim();
        }

        private static void validateIdentifier(String value, String name) {
            if (!MAVEN_ID.matcher(value).matches()) {
                throw new IllegalArgumentException("--" + name + " contains unsupported characters: " + value);
            }
        }

        private static String defaultPackageName(String group, String artifact) {
            String[] segments = artifact.replace('-', '.').split("\\.", -1);
            StringBuilder packageName = new StringBuilder(group.length() + artifact.length() + 1)
                    .append(group);
            for (String segment : segments) {
                packageName.append('.').append(safePackageSegment(segment));
            }
            return packageName.toString();
        }

        private static String safePackageSegment(String segment) {
            if (segment.isEmpty()) return "_";
            String safe = Character.isJavaIdentifierStart(segment.charAt(0))
                    ? segment
                    : "_" + segment;
            return SourceVersion.isKeyword(safe) ? safe + '_' : safe;
        }
    }
}
