package com.reactor.rust.codegen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.Processor;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLClassLoader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactorCodegenProcessorsTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesStartupDescriptorIndexesAndPropertyMetadata() throws Exception {
        Path source = source("generated/fixture/Handler.java", """
                package generated.fixture;

                 import com.reactor.rust.annotations.GetMapping;
                 import com.reactor.rust.annotations.ReactorApplication;
                 import com.reactor.rust.annotations.RequestMapping;
                import com.reactor.rust.annotations.RustProperty;
                 import com.reactor.rust.di.annotation.Component;

                 @ReactorApplication(
                         name = "Inventory API",
                         version = "2.4.0",
                         description = "Low-latency inventory reads")
                 final class Application {}

                @Component
                @RequestMapping("/items")
                public class Handler {
                    @RustProperty(value = "sample.limit", defaultValue = "8")
                    private int limit;

                    @GetMapping("/{id}")
                    public String get() { return "ok"; }
                }
                """);
        Compilation output = compile(source, new ReactorStartupProcessor());

        assertTrue(Files.readString(output.classes().resolve("META-INF/reactor/components.idx"))
                .contains("generated.fixture.Handler"));
        assertTrue(Files.readString(output.classes().resolve("META-INF/reactor/routes.idx"))
                .contains("GET /items/{id}"));
        assertTrue(Files.readString(output.classes().resolve("META-INF/reactor/properties.idx"))
                .contains("sample.limit\tint\t8"));
        String openApi = Files.readString(output.classes().resolve("META-INF/reactor/openapi.json"));
        assertTrue(openApi.contains("\"/items/{id}\""));
         assertTrue(openApi.contains("\"get\""));
         assertTrue(openApi.contains("\"title\":\"Inventory API\""));
         assertTrue(openApi.contains("\"version\":\"2.4.0\""));
         assertTrue(openApi.contains("\"description\":\"Low-latency inventory reads\""));
        assertTrue(Files.readString(output.generated().resolve(
                        "com/reactor/generated/ReactorApplicationDescriptor.java"))
                .contains("generated.fixture.Handler__ReactorFactory.register(container)"));
        String factory = Files.readString(output.generated().resolve(
                "generated/fixture/Handler__ReactorFactory.java"));
        assertTrue(factory.contains("registerGeneratedFactory"));
        assertTrue(factory.contains("GeneratedRouteInvokers.register"));
        assertTrue(factory.contains("((generated.fixture.Handler) bean).get()"));
        assertFalse(factory.contains("markGeneratedReflectionFree"));
    }

    @Test
    void generatesConstructorInjectionControllerAndConfigurationFactories() throws Exception {
        Path source = source("generated/fixture/DeclarativeApplication.java", """
                package generated.fixture;

                import com.reactor.rust.annotations.GetMapping;
                import com.reactor.rust.annotations.PathVariable;
                import com.reactor.rust.annotations.RestController;
                import com.reactor.rust.di.annotation.Bean;
                import com.reactor.rust.di.annotation.Component;
                import com.reactor.rust.di.annotation.Configuration;

                @Component
                final class Repository {}

                final class Service {
                    Service(Repository repository) {}
                }

                final class StatusEndpoint {
                    @GetMapping("/status")
                    String status() { return "UP"; }
                }

                @Configuration
                final class AppConfiguration {
                    @Bean
                    Service service(Repository repository) {
                        return new Service(repository);
                    }

                    @Bean
                    StatusEndpoint statusEndpoint() {
                        return new StatusEndpoint();
                    }
                }

                @RestController("/customers")
                final class CustomerController {
                    private final Service service;

                    CustomerController(Service service) {
                        this.service = service;
                    }

                    @GetMapping("/{id}")
                    String customer(@PathVariable("id") long id) {
                        return Long.toString(id);
                    }
                }

                public final class DeclarativeApplication {}
                """);

        Compilation output = compile(source, new ReactorStartupProcessor());

        String configurationFactory = Files.readString(output.generated().resolve(
                "generated/fixture/AppConfiguration__ReactorFactory.java"));
        assertTrue(configurationFactory.contains(
                "configuration.service(container.getBean(generated.fixture.Repository.class))"));
        assertTrue(configurationFactory.contains("registerGeneratedFactory"));
        assertTrue(configurationFactory.contains(
                "registry.registerBean(container.getBean(generated.fixture.StatusEndpoint.class))"));
        assertTrue(configurationFactory.contains(
                "GeneratedRouteInvokers.register(generated.fixture.StatusEndpoint.class"));
        String controllerFactory = Files.readString(output.generated().resolve(
                "generated/fixture/CustomerController__ReactorFactory.java"));
        assertTrue(controllerFactory.contains(
                "new generated.fixture.CustomerController(container.getBean(generated.fixture.Service.class))"));
        assertTrue(controllerFactory.contains(".customer(((Long) arg0).longValue())"));
        assertTrue(controllerFactory.contains(
                "container.markGeneratedReflectionFree(generated.fixture.CustomerController.class)"));
        assertTrue(Files.readString(output.classes().resolve("META-INF/reactor/routes.idx"))
                .contains("GET /customers/{id}"));
        assertTrue(Files.readString(output.classes().resolve("META-INF/reactor/routes.idx"))
                .contains("GET /status"));
    }

    @Test
    void generatesConditionalBeanFactoriesOptionalInjectionAndActiveRouteMetadata() throws Exception {
        Path source = source("generated/fixture/ConditionalBeansApplication.java", """
                package generated.fixture;

                import com.reactor.rust.annotations.GetMapping;
                import com.reactor.rust.annotations.RequiresProperty;
                import com.reactor.rust.annotations.RestController;
                import com.reactor.rust.di.annotation.Bean;
                import com.reactor.rust.di.annotation.Component;
                import com.reactor.rust.di.annotation.Configuration;
                import java.util.Optional;

                final class OptionalService {}

                @Configuration
                final class ConditionalConfiguration {
                    @Bean
                    @RequiresProperty(name = "sample.optional.enabled", value = "true")
                    OptionalService optionalService() { return new OptionalService(); }
                }

                @Component
                final class OptionalConsumer {
                    OptionalConsumer(Optional<OptionalService> service) {}
                }

                @RestController("/conditional")
                @RequiresProperty(name = "sample.routes.enabled", value = "true")
                final class ConditionalController {
                    @GetMapping("") String get() { return "ok"; }
                }

                public final class ConditionalBeansApplication {}
                """);
        Compilation output = compile(source, new ReactorStartupProcessor());

        String configurationFactory = Files.readString(output.generated().resolve(
                "generated/fixture/ConditionalConfiguration__ReactorFactory.java"));
        assertTrue(configurationFactory.contains(
                "if (!container.hasBean(generated.fixture.ConditionalConfiguration.class)) return 0"));
        assertTrue(configurationFactory.contains(
                "ConfigurationBinder.matches(\"sample.optional.enabled\", \"true\", false)"));

        String consumerFactory = Files.readString(output.generated().resolve(
                "generated/fixture/OptionalConsumer__ReactorFactory.java"));
        assertTrue(consumerFactory.contains("container.hasBean(generated.fixture.OptionalService.class)"));
        assertTrue(consumerFactory.contains("java.util.Optional.empty()"));

        String descriptor = Files.readString(output.generated().resolve(
                "com/reactor/generated/ReactorApplicationDescriptor.java"));
        assertTrue(descriptor.contains("isComponentEnabled(String componentType)"));
        assertTrue(descriptor.contains("case \"generated.fixture.ConditionalController\""));
    }

    @Test
    void generatesTypedConfigurationAndStartupConditionsWithoutReflection() throws Exception {
        Path source = source("generated/fixture/TypedConfigurationApplication.java", """
                package generated.fixture;

                import com.reactor.rust.annotations.ConfigDefault;
                import com.reactor.rust.annotations.ConfigurationProperties;
                import com.reactor.rust.annotations.Profile;
                import com.reactor.rust.annotations.RequiresProperty;
                import com.reactor.rust.di.annotation.Component;

                @ConfigurationProperties("sample.client")
                record ClientProperties(
                        @ConfigDefault("8") int workers,
                        @ConfigDefault("250ms") java.time.Duration timeout,
                        java.util.Optional<String> endpoint) {}

                @Component
                @Profile({"micro-rest", "micro-dubbo"})
                @RequiresProperty(name = "sample.client.enabled", value = "true")
                final class ConditionalClient {
                    ConditionalClient(ClientProperties properties) {}
                }

                public final class TypedConfigurationApplication {}
                """);

        Compilation output = compile(source, new ReactorStartupProcessor());

        String configurationFactory = Files.readString(output.generated().resolve(
                "generated/fixture/ClientProperties__ReactorFactory.java"));
        assertTrue(configurationFactory.contains(
                "ConfigurationBinder.integer(\"sample.client.workers\", \"8\")"));
        assertTrue(configurationFactory.contains(
                "ConfigurationBinder.duration(\"sample.client.timeout\", \"250ms\")"));
        assertTrue(configurationFactory.contains(
                "ConfigurationBinder.optionalString(\"sample.client.endpoint\")"));

        String conditionalFactory = Files.readString(output.generated().resolve(
                "generated/fixture/ConditionalClient__ReactorFactory.java"));
        assertTrue(conditionalFactory.contains(
                "ConfigurationBinder.matches(\"sample.client.enabled\", \"true\", false)"));
        assertTrue(conditionalFactory.contains(
                "ConfigurationBinder.profileMatches(\"micro-rest\", \"micro-dubbo\")"));

        String metadata = Files.readString(output.classes().resolve(
                "META-INF/reactor/configuration-metadata.json"));
        assertTrue(metadata.contains("sample.client.workers"));
        assertTrue(metadata.contains("250ms"));
    }

    @Test
    void generatesAndExecutesRecordValidationWithoutRuntimeReflection() throws Exception {
        Path source = source("generated/fixture/ValidatedRequest.java", """
                package generated.fixture;

                import com.reactor.rust.annotations.Field;
                import com.reactor.rust.annotations.Min;
                import com.reactor.rust.annotations.NotBlank;
                import com.reactor.rust.annotations.Request;
                import com.reactor.rust.annotations.Size;

                @Request
                public record ValidatedRequest(
                        @NotBlank String name,
                        @Min(1) int quantity,
                        @Size(min = 2, max = 4) java.util.List<String> tags,
                        @Field(defaultValue = "PENDING") String status) {}
                """);

        Compilation output = compile(source, new ReactorStartupProcessor());
        String generated = Files.readString(output.generated().resolve(
                "generated/fixture/ValidatedRequest__ReactorValidator.java"));
        assertTrue(generated.contains("value.name()"));
        assertTrue(generated.contains("field1 < 1L"));
        assertTrue(generated.contains("GeneratedValidationSupport.length(field2)"));
        assertFalse(generated.contains("java.lang.reflect"));

        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[]{output.classes().toUri().toURL()},
                getClass().getClassLoader())) {
            Class<?> validator = loader.loadClass("generated.fixture.ValidatedRequest__ReactorValidator");
            validator.getMethod("register").invoke(null);
            Class<?> requestType = loader.loadClass("generated.fixture.ValidatedRequest");
            Object invalid = requestType.getConstructors()[0]
                    .newInstance(" ", 0, List.of("one"), null);

            com.reactor.rust.validation.ValidationResult result =
                    com.reactor.rust.validation.DTOValidator.getInstance().validate(invalid);

            assertFalse(result.isValid());
            assertEquals(3, result.getViolations().size());
            assertEquals("PENDING", com.reactor.rust.validation.DTOValidator.getInstance()
                    .getDefaultValue(requestType, "status"));
        }
    }

    @Test
    void generatedFactoriesSupportCheckedExceptionsWithoutReflection() throws Exception {
        Path source = source("generated/fixture/CheckedApplication.java", """
                package generated.fixture;

                import com.reactor.rust.di.annotation.Bean;
                import com.reactor.rust.di.annotation.Component;
                import com.reactor.rust.di.annotation.Configuration;

                @Component
                final class CheckedRepository {
                    CheckedRepository() throws java.io.IOException {}
                }

                final class CheckedService {}

                @Configuration
                final class CheckedConfiguration {
                    @Bean
                    CheckedService checkedService() throws java.io.IOException {
                        return new CheckedService();
                    }
                }

                public final class CheckedApplication {}
                """);

        Compilation output = compile(source, new ReactorStartupProcessor());

        String repositoryFactory = Files.readString(output.generated().resolve(
                "generated/fixture/CheckedRepository__ReactorFactory.java"));
        assertTrue(repositoryFactory.contains("GeneratedBeanFactories.create"));
        assertTrue(repositoryFactory.contains("new generated.fixture.CheckedRepository()"));
        String configurationFactory = Files.readString(output.generated().resolve(
                "generated/fixture/CheckedConfiguration__ReactorFactory.java"));
        assertTrue(configurationFactory.contains(
                "GeneratedBeanFactories.create(\"generated.fixture.CheckedConfiguration#checkedService\""));
    }

    @Test
    void generatesExceptionHandlerInvokersWithoutMethodInvoke() throws Exception {
        Path source = source("generated/fixture/ExceptionApplication.java", """
                package generated.fixture;

                import com.reactor.rust.di.annotation.Component;
                import com.reactor.rust.exception.ExceptionHandler;

                final class MissingCustomer extends RuntimeException {}

                @Component
                final class GlobalErrors {
                    @ExceptionHandler(MissingCustomer.class)
                    String missing(MissingCustomer error) { return error.getMessage(); }

                    @ExceptionHandler
                    String fallback(Throwable error) { return "failed"; }
                }

                public final class ExceptionApplication {}
                """);

        Compilation output = compile(source, new ReactorStartupProcessor());

        String factory = Files.readString(output.generated().resolve(
                "generated/fixture/GlobalErrors__ReactorFactory.java"));
        assertTrue(factory.contains("registry.registerGenerated"));
        assertTrue(factory.contains("generated.fixture.MissingCustomer.class"));
        assertTrue(factory.contains("((generated.fixture.GlobalErrors) bean).missing("));
        assertTrue(factory.contains("java.lang.Throwable.class"));
        assertFalse(factory.contains("java.lang.reflect"));
        String descriptor = Files.readString(output.generated().resolve(
                "com/reactor/generated/ReactorApplicationDescriptor.java"));
        assertTrue(descriptor.contains("registerExceptionHandlers(container, registry)"));
    }

    @Test
    void generatesStrictNativePrimitiveBindingFromStandardAnnotations() throws Exception {
        Path source = source("generated/fixture/PrimitiveApplication.java", """
                package generated.fixture;

                import com.reactor.rust.annotations.GetMapping;
                import com.reactor.rust.annotations.PathVariable;
                import com.reactor.rust.annotations.RequestParam;
                import com.reactor.rust.annotations.RestController;

                @RestController("/items")
                final class PrimitiveController {
                    @GetMapping("/search")
                    String search(@RequestParam(value = "page", defaultValue = "1") int page) {
                        return Integer.toString(page);
                    }

                    @GetMapping("/{id}")
                    String find(@PathVariable("id") long id) {
                        return Long.toString(id);
                    }
                }

                public final class PrimitiveApplication {}
                """);

        Compilation output = compile(source, new ReactorStartupProcessor());

        String factory = Files.readString(output.generated().resolve(
                "generated/fixture/PrimitiveController__ReactorFactory.java"));
        assertTrue(factory.contains("invokeInt(Object bean, int value)"));
        assertTrue(factory.contains("invokeLong(Object bean, long value)"));
        assertTrue(factory.contains("GeneratedPrimitiveBindings.register"));
        assertTrue(factory.contains("GeneratedPrimitiveBinding.Source.QUERY"));
        assertTrue(factory.contains("GeneratedPrimitiveBinding.Mode.STRICT_DEFAULT"));
        assertTrue(factory.contains("GeneratedPrimitiveBinding.Source.PATH"));
        assertTrue(factory.contains("GeneratedPrimitiveBinding.Mode.STRICT_REQUIRED"));
        assertFalse(factory.contains("DirectQueryInt"));
    }

    @Test
    void generatesDirectJsonWriterAndProvider() throws Exception {
        Path source = source("generated/fixture/Response.java", """
                package generated.fixture;
                import com.reactor.rust.annotations.GenerateDirectJsonWriter;
                @GenerateDirectJsonWriter
                public record Response(long id, String name, boolean active) {}
                """);
        Compilation output = compile(source, new DirectJsonWriterProcessor());

        String writer = Files.readString(output.generated().resolve(
                "generated/fixture/ResponseDirectJsonWriter.java"));
        assertTrue(writer.contains("json.fieldLong(\"id\", value.id())"));
        assertTrue(writer.contains("json.fieldString(\"name\", value.name())"));
        assertTrue(Files.readString(output.classes().resolve(
                        "META-INF/services/com.reactor.rust.json.DirectJsonWriterProvider"))
                .contains("ReactorDirectJsonWriterProvider"));
    }

    @Test
    void generatedDirectJsonWriterSupportsNullableBoxedScalars() throws Exception {
        Path source = source("generated/fixture/NullableResponse.java", """
                package generated.fixture;
                import com.reactor.rust.annotations.GenerateDirectJsonWriter;
                @GenerateDirectJsonWriter
                public record NullableResponse(
                        Integer count, Long total, Double ratio, Boolean enabled) {}
                """);

        Compilation output = compile(source, new DirectJsonWriterProcessor());

        String generated = Files.readString(output.generated().resolve(
                "generated/fixture/NullableResponseDirectJsonWriter.java"));
        assertTrue(generated.contains("count() == null"));
        assertTrue(generated.contains("count().intValue()"));
        assertTrue(generated.contains("enabled().booleanValue()"));
    }

    @Test
    void responseRecordGetsDirectWriterWithoutSecondAnnotation() throws Exception {
        Path source = source("generated/fixture/AutomaticResponse.java", """
                package generated.fixture;
                import com.reactor.rust.annotations.Response;
                @Response
                public record AutomaticResponse(long id, String status) {}
                """);

        Compilation output = compile(source, new DirectJsonWriterProcessor());

        String generated = Files.readString(output.generated().resolve(
                "generated/fixture/AutomaticResponseDirectJsonWriter.java"));
        assertTrue(generated.contains("json.fieldLong(\"id\", value.id())"));
        assertTrue(generated.contains("json.fieldString(\"status\", value.status())"));
    }

    @Test
    void generatesJdbcRecordMapper() throws Exception {
        Path source = source("generated/fixture/Customer.java", """
                package generated.fixture;
                import com.reactor.rust.annotations.GenerateJdbcMapper;
                @GenerateJdbcMapper
                public record Customer(long id, String customerNo, java.time.Instant createdAt) {}
                """);
        Compilation output = compile(source, new JdbcRecordMapperProcessor());

        String mapper = Files.readString(output.generated().resolve(
                "generated/fixture/CustomerJdbcMapper.java"));
        assertTrue(mapper.contains("row.getLong(\"id\")"));
        assertTrue(mapper.contains("row.getString(\"customer_no\")"));
        assertTrue(mapper.contains("instant(row, \"created_at\")"));
    }

    @Test
    void generatesDeclarativeHttpClientWithoutProxyOrReflection() throws Exception {
        Path annotations = source("com/reactor/rust/http/client/ReactorHttpClient.java", """
                package com.reactor.rust.http.client;
                @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
                public @interface ReactorHttpClient {
                    String name() default "";
                    String baseUrlProperty();
                }
                """);
        Path exchange = source("com/reactor/rust/http/client/HttpExchange.java", """
                package com.reactor.rust.http.client;
                @java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
                public @interface HttpExchange {
                    com.reactor.rust.annotations.HttpMethod method() default com.reactor.rust.annotations.HttpMethod.GET;
                    String path();
                    long timeoutMs() default 0L;
                    int retries() default -1;
                    boolean idempotent() default false;
                    String contentType() default "application/json; charset=utf-8";
                    String accept() default "application/json";
                }
                """);
        Path response = source("com/reactor/rust/http/client/HttpClientResponse.java", """
                package com.reactor.rust.http.client;
                public record HttpClientResponse<T>(int status, Object headers, T body) {}
                """);
        Path runtime = source("com/reactor/rust/http/client/ReactorHttpClientRuntime.java", """
                package com.reactor.rust.http.client;
                public final class ReactorHttpClientRuntime {
                    public Client client(String property) { return new Client(); }
                    public static final class Client {
                        public Request request(String method, String path, String contentType, String accept,
                                long timeoutMs, int retries, boolean idempotent) { return new Request(); }
                    }
                    public static final class Request {
                        public Request path(String name, Object value) { return this; }
                        public Request query(String name, Object value) { return this; }
                        public Request header(String name, Object value) { return this; }
                        public Request body(Object value) { return this; }
                        public <T> java.util.concurrent.CompletionStage<T> execute(Class<T> type) { return null; }
                        public <T> java.util.concurrent.CompletionStage<HttpClientResponse<T>> executeResponse(Class<T> type) { return null; }
                        public <T> java.util.concurrent.CompletionStage<java.util.List<T>> executeList(Class<T> type) { return null; }
                        public <T> java.util.concurrent.CompletionStage<HttpClientResponse<java.util.List<T>>> executeListResponse(Class<T> type) { return null; }
                    }
                }
                """);
        Path client = source("generated/fixture/CustomerClient.java", """
                package generated.fixture;
                import com.reactor.rust.annotations.PathVariable;
                import com.reactor.rust.annotations.RequestBody;
                import com.reactor.rust.annotations.HeaderParam;
                import com.reactor.rust.annotations.RequestParam;
                import com.reactor.rust.http.client.HttpExchange;
                import com.reactor.rust.http.client.ReactorHttpClient;
                import java.util.concurrent.CompletionStage;

                record Customer(long id, String name) {}
                record CreateCustomer(String name) {}

                @ReactorHttpClient(name = "customerClient", baseUrlProperty = "clients.customer.base-url")
                public interface CustomerClient {
                    @HttpExchange(path = "/customers/{id}")
                    CompletionStage<Customer> get(
                            @PathVariable("id") long id,
                            @RequestParam(value = "expand", required = false) String expand,
                            @HeaderParam("x-tenant") String tenant);

                    @HttpExchange(method = com.reactor.rust.annotations.HttpMethod.POST,
                            path = "/customers", retries = 0)
                    CompletionStage<Customer> create(@RequestBody CreateCustomer request);

                    @HttpExchange(path = "/customers")
                    CompletionStage<java.util.List<Customer>> list(
                            @RequestParam(value = "segment", required = false) java.util.Optional<String> segment);
                }
                """);

        Compilation output = compile(
                List.of(annotations, exchange, response, runtime, client),
                new ReactorStartupProcessor());

        String generated = Files.readString(output.generated().resolve(
                "generated/fixture/CustomerClient__ReactorHttpClient.java"));
        assertTrue(generated.contains("request.path(\"id\", arg0)"));
        assertTrue(generated.contains("request.query(\"expand\", arg1)"));
        assertTrue(generated.contains("request.header(\"x-tenant\", arg2)"));
        assertTrue(generated.contains("request.body(arg0)"));
        assertTrue(generated.contains("request.query(\"segment\", arg0.orElse(null))"));
        assertTrue(generated.contains("request.executeList(generated.fixture.Customer.class)"));
        assertFalse(generated.contains("java.lang.reflect"));
        assertFalse(generated.contains("Proxy"));
        assertTrue(Files.readString(output.classes().resolve("META-INF/reactor/properties.idx"))
                .contains("clients.customer.base-url"));
    }

    @Test
    void generatesApplicationScopedSchedulerRegistration() throws Exception {
        Path registry = source("com/reactor/rust/scheduler/ScheduledTaskRegistry.java", """
                package com.reactor.rust.scheduler;
                public final class ScheduledTaskRegistry {
                    public void register(String name, Runnable task,
                            com.reactor.rust.annotations.Scheduled.Mode mode,
                            long intervalMs, String intervalProperty,
                            long initialDelayMs, String initialDelayProperty,
                            String lockName, long lockAtMostMs, String lockAtMostProperty) {}
                }
                """);
        Path application = source("generated/fixture/ScheduledApplication.java", """
                package generated.fixture;
                import com.reactor.rust.annotations.Scheduled;
                import com.reactor.rust.di.annotation.Component;

                @Component
                final class CatalogRefresh {
                    @Scheduled(intervalProperty = "catalog.refresh-ms",
                            initialDelayProperty = "catalog.initial-delay-ms",
                            lockName = "catalog-refresh",
                            lockAtMostProperty = "catalog.lock-at-most-ms")
                    void refresh() {}
                }

                public final class ScheduledApplication {}
                """);

        Compilation output = compile(List.of(registry, application), new ReactorStartupProcessor());

        String generated = Files.readString(output.generated().resolve(
                "generated/fixture/CatalogRefresh__ReactorFactory.java"));
        assertTrue(generated.contains("container.getBean(com.reactor.rust.scheduler.ScheduledTaskRegistry.class)"));
        assertTrue(generated.contains("bean::refresh"));
        assertFalse(generated.contains("GeneratedScheduledTasks"));
    }

    @Test
    void rejectsLegacyMiddlewareAtBuildTime() throws Exception {
        Path source = source("generated/fixture/LegacyFilter.java", """
                package generated.fixture;

                import com.reactor.rust.middleware.Middleware;
                import com.reactor.rust.middleware.MiddlewareChain;
                import com.reactor.rust.middleware.MiddlewareContext;

                final class LegacyFilter implements Middleware {
                    public MiddlewareChain.Result process(MiddlewareContext context, MiddlewareChain chain) {
                        return chain.next(context);
                    }
                }
                """);

        String diagnostics = compileFailure(source, new ReactorStartupProcessor());

        assertTrue(diagnostics.contains("Legacy Middleware is not connected to the native request path"));
    }

    private Path source(String relativePath, String content) throws Exception {
        Path source = tempDir.resolve(relativePath);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content, StandardCharsets.UTF_8);
        return source;
    }

    private Compilation compile(Path source, Processor processor) throws Exception {
        return compile(List.of(source), processor);
    }

    private Compilation compile(List<Path> sources, Processor processor) throws Exception {
        String name = processor.getClass().getSimpleName();
        Path generated = Files.createDirectories(tempDir.resolve(name + "-generated"));
        Path classes = Files.createDirectories(tempDir.resolve(name + "-classes"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    files,
                    null,
                    List.of(
                            "--release", "21",
                            "-proc:full",
                            "-classpath", System.getProperty("java.class.path"),
                            "-d", classes.toString(),
                            "-s", generated.toString()),
                    null,
                    files.getJavaFileObjectsFromFiles(sources.stream().map(Path::toFile).toList()));
            task.setProcessors(List.of(processor));
            assertTrue(task.call());
        }
        return new Compilation(generated, classes);
    }

    private String compileFailure(Path source, Processor processor) throws Exception {
        Path generated = Files.createDirectories(tempDir.resolve("failure-generated"));
        Path classes = Files.createDirectories(tempDir.resolve("failure-classes"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(
                diagnostics, null, StandardCharsets.UTF_8)) {
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    files,
                    diagnostics,
                    List.of(
                            "--release", "21",
                            "-proc:full",
                            "-classpath", System.getProperty("java.class.path"),
                            "-d", classes.toString(),
                            "-s", generated.toString()),
                    null,
                    files.getJavaFileObjects(source.toFile()));
            task.setProcessors(List.of(processor));
            assertFalse(task.call());
        }
        return diagnostics.getDiagnostics().stream()
                .map(diagnostic -> diagnostic.getMessage(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private record Compilation(Path generated, Path classes) {}
}
