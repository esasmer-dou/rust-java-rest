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
        assertTrue(factory.contains("new com.reactor.rust.bridge.GeneratedRouteMetadata("
                + "\"GET\", \"/items/{id}\", java.lang.Void.class, java.lang.String.class"));
        assertTrue(factory.contains("GeneratedRouteInvokers.register(generated.fixture.Handler.class"));
        assertTrue(factory.contains("new com.reactor.generated.ReactorApplicationDescriptor__Dispatch.Route0("));
        assertFalse(factory.contains("new com.reactor.rust.bridge.GeneratedRouteInvoker()"));
        String dispatch = Files.readString(output.generated().resolve(
                "com/reactor/generated/ReactorApplicationDescriptor__Dispatch.java"));
        assertTrue(dispatch.contains("return ((generated.fixture.Handler) bean).get();"));
        assertTrue(dispatch.contains("@Override public Object invoke0(Object bean) throws Throwable {\n"
                + "            switch (routeId) {"));
        assertFalse(dispatch.contains("public static Object dispatch"));
        assertFalse(dispatch.contains("return dispatch"));
        assertFalse(dispatch.contains("java.lang.reflect"));
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
    void registersRoutesOnExplicitBeanTypesFromDependenciesWithoutScanning() throws Exception {
        Path source = source("generated/fixture/HealthConfiguration.java", """
                package generated.fixture;
                import com.reactor.rust.di.annotation.Bean;
                import com.reactor.rust.di.annotation.Configuration;
                import com.reactor.rust.health.HealthEndpoint;
                import com.reactor.rust.health.HealthStarter;

                @Configuration
                public final class HealthConfiguration {
                    @Bean
                    public HealthEndpoint health() {
                        return HealthStarter.application("fixture").build();
                    }
                }
                """);
        Compilation output = compile(source, new ReactorStartupProcessor());
        String routes = Files.readString(output.classes().resolve("META-INF/reactor/routes.idx"));
        assertTrue(routes.contains("GET /app/health"));
        assertTrue(routes.contains("GET /app/liveness"));
        assertTrue(routes.contains("GET /app/readiness"));
        String factory = Files.readString(output.generated().resolve(
                "generated/fixture/HealthConfiguration__ReactorFactory.java"));
        assertTrue(factory.contains("registry.registerBean(container.getBean(com.reactor.rust.health.HealthEndpoint.class))"));
        assertTrue(factory.contains("GeneratedRouteInvokers.register(com.reactor.rust.health.HealthEndpoint.class"));
        assertTrue(factory.contains("new com.reactor.generated.ReactorApplicationDescriptor__Dispatch.Route0("));
        assertFalse(factory.contains("new com.reactor.rust.bridge.GeneratedRouteInvoker()"));
        String dispatch = Files.readString(output.generated().resolve(
                "com/reactor/generated/ReactorApplicationDescriptor__Dispatch.java"));
        for (String name : List.of("health", "liveness", "readiness")) {
            assertTrue(dispatch.contains("return ((com.reactor.rust.health.HealthEndpoint) bean)." + name + "();"));
        }
        assertTrue(dispatch.contains("@Override public Object invoke0(Object bean) throws Throwable {\n"
                + "            switch (routeId) {"));
        assertFalse(dispatch.contains("public static Object dispatch"));
        assertFalse(dispatch.contains("return dispatch"));
        assertFalse(dispatch.contains("java.lang.reflect"));
        assertFalse(factory.contains("new com.reactor.rust.health.HealthEndpoint("));
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
        assertTrue(writer.contains("private static final byte[] FIELD_PREFIXES"));
        assertTrue(writer.contains("json.fieldPrefix(FIELD_PREFIXES, 0, 5)"));
        assertTrue(writer.contains("json.number(value.id())"));
        assertTrue(writer.contains("json.string(value.name())"));
        assertTrue(!writer.contains("json.fieldLong(\"id\""));
        assertTrue(writer.contains("ResponseDirectJsonWriter INSTANCE"));
        Path providerPath;
        try (var generatedFiles = Files.walk(output.generated().resolve("com/reactor/generated"))) {
            providerPath = generatedFiles
                    .filter(path -> path.getFileName().toString().startsWith("ReactorDirectJsonWriterProvider"))
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .findFirst()
                    .orElseThrow();
        }
        String provider = Files.readString(providerPath);
        assertTrue(!provider.contains("boolean supports(Class<?> type)"));
        assertTrue(provider.contains("findWriter(Class<?> type)"));
        assertTrue(provider.contains("ResponseDirectJsonWriter.INSTANCE"));
        assertTrue(!provider.contains("private static final ResponseDirectJsonWriter"));
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
        assertTrue(generated.contains("var number_0 = value.count()"));
        assertTrue(generated.contains("number_0.intValue()"));
        assertTrue(generated.contains("boolean_3.booleanValue()"));
    }

    @Test
    void responseRecordDoesNotImplicitlySelectDirectWriter() throws Exception {
        Path source = source("generated/fixture/AutomaticResponse.java", """
                package generated.fixture;
                import com.reactor.rust.annotations.Response;
                @Response
                public record AutomaticResponse(long id, String status) {}
                """);

        Compilation output = compile(source, new DirectJsonWriterProcessor());

        assertFalse(Files.exists(output.generated().resolve(
                "generated/fixture/AutomaticResponseDirectJsonWriter.java")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void generatedDirectJsonWriterSupportsNestedCollectionsArraysOptionalAndDecimal() throws Exception {
        Path source = source("generated/fixture/ComplexResponse.java", """
                package generated.fixture;
                import com.reactor.rust.annotations.GenerateDirectJsonWriter;
                @GenerateDirectJsonWriter
                public record ComplexResponse(
                        Address address,
                        java.util.List<Address> items,
                        String[] tags,
                        java.util.Optional<String> note,
                        java.math.BigDecimal total) {
                    public record Address(String city, int code) {}
                }
                """);

        Compilation output = compile(source, new DirectJsonWriterProcessor());
        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[] {output.classes().toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> addressType = loader.loadClass("generated.fixture.ComplexResponse$Address");
            Object address = addressType.getConstructor(String.class, int.class)
                    .newInstance("İstanbul", 34);
            Class<?> responseType = loader.loadClass("generated.fixture.ComplexResponse");
            Object response = responseType.getConstructor(
                            addressType, List.class, String[].class,
                            java.util.Optional.class, java.math.BigDecimal.class)
                    .newInstance(
                            address,
                            List.of(address),
                            new String[] {"one", "iki"},
                            java.util.Optional.of("hazır"),
                            new java.math.BigDecimal("123.45"));
            com.reactor.rust.json.DirectJsonWriter<Object> writer =
                    (com.reactor.rust.json.DirectJsonWriter<Object>) loader
                            .loadClass("generated.fixture.ComplexResponseDirectJsonWriter")
                            .getConstructor()
                            .newInstance();
            java.nio.ByteBuffer out = java.nio.ByteBuffer.allocate(1024);

            int written = writer.write(response, out, 0);
            byte[] bytes = new byte[written];
            out.position(0);
            out.get(bytes);

            assertEquals(
                    "{\"address\":{\"city\":\"İstanbul\",\"code\":34},"
                            + "\"items\":[{\"city\":\"İstanbul\",\"code\":34}],"
                            + "\"tags\":[\"one\",\"iki\"],\"note\":\"hazır\",\"total\":123.45}",
                    new String(bytes, StandardCharsets.UTF_8));
        }
    }

    @Test
    void explicitDirectWriterRejectsRecursiveGraphs() throws Exception {
        Path source = source("generated/fixture/RecursiveResponse.java", """
                package generated.fixture;
                import com.reactor.rust.annotations.GenerateDirectJsonWriter;
                @GenerateDirectJsonWriter
                public record RecursiveResponse(String name, RecursiveResponse child) {}
                """);

        String failure = compileFailure(source, new DirectJsonWriterProcessor());

        assertTrue(failure.contains("unsupported or recursive component"));
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
                        public PreparedRequest prepare(String method, String path, String contentType, String accept,
                                long timeoutMs, int retries, boolean idempotent) { return new PreparedRequest(); }
                    }
                    public static final class PreparedRequest {
                        public Request request() { return new Request(); }
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
        assertFalse(generated.contains("CircuitBreaker"));
        assertFalse(generated.contains("acquirePermission"));
        assertTrue(Files.readString(output.classes().resolve("META-INF/reactor/properties.idx"))
                .contains("clients.customer.base-url"));
    }

    @Test
    void generatesOnlyAnnotatedMethodBreakersWithFullMethodConfigurationOverride() throws Exception {
        Path client = source("generated/fixture/AllMethods.java", """
                package generated.fixture;
                import com.reactor.rust.http.client.*;
                import java.util.concurrent.CompletionStage;
                @ReactorHttpClient(baseUrlProperty="test.http.base")
                @HttpCircuitBreaker(windowSize=32, minimumCalls=8, failureRateThreshold=75,
                                    openDurationMs=1234, halfOpenMaxCalls=3, record5xxAsFailure=false)
                public interface AllMethods {
                    @HttpExchange(path="/one") CompletionStage<String> one();
                    @HttpExchange(path="/two") @HttpCircuitBreaker
                    CompletionStage<HttpClientResponse<java.util.List<String>>> two();
                    @HttpExchange(path="/overload") CompletionStage<String> one(
                        @com.reactor.rust.annotations.RequestParam("q") String q);
                }
                """);
        Path selectiveClient = source("generated/fixture/Selective.java", """
                package generated.fixture;
                import com.reactor.rust.http.client.*;
                import java.util.concurrent.CompletionStage;
                @ReactorHttpClient(baseUrlProperty="test.http.base")
                public interface Selective {
                    @HttpExchange(path="/plain") CompletionStage<String> plain();
                    @HttpExchange(path="/guarded") @HttpCircuitBreaker
                    CompletionStage<java.util.List<String>> guarded();
                    @HttpExchange(path="/raw") @HttpCircuitBreaker
                    CompletionStage<HttpClientResponse<String>> raw();
                }
                """);
        Compilation output = compile(httpSources(client, selectiveClient), new ReactorStartupProcessor());
        String all = Files.readString(output.generated().resolve("generated/fixture/AllMethods__ReactorHttpClient.java"));
        assertEquals(2, all.split("CircuitBreakerState\\(32, 8, 75, 1234L, 3, false\\)", -1).length - 1);
        assertTrue(all.contains("CircuitBreakerState(64, 16, 50, 10000L, 2, true)"));
        assertTrue(all.contains("breaker0.acquirePermission()"));
        assertTrue(all.contains("breaker1.acquirePermission()"));
        assertTrue(all.contains("breaker2.acquirePermission()"));
        assertTrue(all.contains("HttpCircuitBreakerInvocation.executeListResponse(request, java.lang.String.class, breaker1, lease)"));
        assertTrue(all.indexOf("breaker0.acquirePermission()") < all.indexOf("client.request("));
        assertTrue(all.indexOf("breaker0.acquirePermission()") < all.indexOf("requestPlan0.request()"));
        assertTrue(all.contains("this.requestPlan0 = client.prepare("));
        assertTrue(all.contains("this.requestPlan1 = client.prepare("));
        assertFalse(all.contains("requestPlan2"), "Parameterized methods must not freeze request arguments");
        assertTrue(all.contains("catch (RuntimeException | Error failure)"));
        assertTrue(all.contains("breaker0.recordFailure(lease)"));
        assertFalse(all.contains("java.lang.reflect"));
        String selective = Files.readString(output.generated().resolve("generated/fixture/Selective__ReactorHttpClient.java"));
        assertFalse(selective.contains("CircuitBreakerState breaker0"));
        assertTrue(selective.contains("CircuitBreakerState breaker1"));
        assertTrue(selective.contains("CircuitBreakerState breaker2"));
        String plain = selective.substring(selective.indexOf(" plain()"), selective.indexOf(" guarded()"));
        assertFalse(plain.contains("lease"));
        assertFalse(plain.contains("try {"));
        assertTrue(plain.contains("return request.execute(java.lang.String.class)"));
        assertTrue(selective.contains("HttpCircuitBreakerInvocation.executeList(request, java.lang.String.class, breaker1, lease)"));
        assertTrue(selective.contains("HttpCircuitBreakerInvocation.executeResponse(request, java.lang.String.class, breaker2, lease)"));
    }

    @Test
    void rejectsInvalidCircuitConfigurationsAndUnsupportedAnnotationTargetsAtBuildTime() throws Exception {
        Path client = source("generated/fixture/InvalidCircuits.java", """
                package generated.fixture;
                import com.reactor.rust.http.client.*;
                import java.util.concurrent.CompletionStage;
                @HttpCircuitBreaker class NotAClient {}
                @ReactorHttpClient(baseUrlProperty="test") @HttpCircuitBreaker
                interface Generic<T> { @HttpExchange(path="/") CompletionStage<String> call(); }
                interface Parent { CompletionStage<String> inherited(); }
                @ReactorHttpClient(baseUrlProperty="test") @HttpCircuitBreaker
                interface Child extends Parent { @HttpExchange(path="/") CompletionStage<String> call(); }
                @ReactorHttpClient(baseUrlProperty="test") @HttpCircuitBreaker
                interface MarkedParent { @HttpExchange(path="/") CompletionStage<String> call(); }
                @ReactorHttpClient(baseUrlProperty="test")
                interface SilentChild extends MarkedParent { @HttpExchange(path="/") CompletionStage<String> call(); }
                @ReactorHttpClient(baseUrlProperty="test") interface InvalidMethods {
                    @HttpCircuitBreaker default CompletionStage<String> defaultCall() { return null; }
                    @HttpCircuitBreaker static CompletionStage<String> staticCall() { return null; }
                    @HttpCircuitBreaker private CompletionStage<String> privateCall() { return null; }
                    @HttpCircuitBreaker CompletionStage<String> missingExchange();
                    @HttpExchange(path="/") @HttpCircuitBreaker(windowSize=65) CompletionStage<String> badWindow();
                    @HttpExchange(path="/") @HttpCircuitBreaker(windowSize=0) CompletionStage<String> zeroWindow();
                    @HttpExchange(path="/") @HttpCircuitBreaker(minimumCalls=0) CompletionStage<String> zeroMinimum();
                    @HttpExchange(path="/") @HttpCircuitBreaker(windowSize=3) CompletionStage<String> badMinimum();
                    @HttpExchange(path="/") @HttpCircuitBreaker(failureRateThreshold=0) CompletionStage<String> zeroRate();
                    @HttpExchange(path="/") @HttpCircuitBreaker(failureRateThreshold=101) CompletionStage<String> badRate();
                    @HttpExchange(path="/") @HttpCircuitBreaker(openDurationMs=0) CompletionStage<String> zeroDuration();
                    @HttpExchange(path="/") @HttpCircuitBreaker(openDurationMs=9223372036855L) CompletionStage<String> overflow();
                    @HttpExchange(path="/") @HttpCircuitBreaker(halfOpenMaxCalls=0) CompletionStage<String> zeroProbes();
                    @HttpExchange(path="/") @HttpCircuitBreaker(halfOpenMaxCalls=65) CompletionStage<String> badProbes();
                    @HttpExchange(path="/") @HttpCircuitBreaker <T> CompletionStage<String> genericCall();
                }
                """);
        String diagnostics = compileFailure(httpSources(client), new ReactorStartupProcessor());
        assertTrue(diagnostics.contains("top-level, non-generic @ReactorHttpClient"));
        assertTrue(diagnostics.contains("without inherited methods"));
        assertTrue(diagnostics.contains("interface SilentChild"));
        assertTrue(diagnostics.contains("requires an abstract @HttpExchange method"));
        assertTrue(diagnostics.contains("Invalid @HttpCircuitBreaker configuration"));
        assertTrue(diagnostics.contains("without type parameters"));
        for (String method : List.of("badWindow", "zeroWindow", "zeroMinimum", "badMinimum", "zeroRate", "badRate",
                "zeroDuration", "overflow", "zeroProbes", "badProbes")) {
            // The diagnostic includes the element location via Diagnostic.toString().
            assertTrue(diagnostics.contains(method), diagnostics);
        }
    }

    private List<Path> httpSources(Path... fixtures) throws Exception {
        try (var sources = Files.walk(Path.of("platform/starter-http-client/src/main/java"))) {
            var files = new java.util.ArrayList<>(sources.filter(path -> path.toString().endsWith(".java")).toList());
            files.addAll(List.of(fixtures));
            return files;
        }
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
        return compileFailure(List.of(source), processor);
    }

    private String compileFailure(List<Path> sources, Processor processor) throws Exception {
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
                    files.getJavaFileObjectsFromPaths(sources));
            task.setProcessors(List.of(processor));
            assertFalse(task.call());
        }
        return diagnostics.getDiagnostics().stream()
                .map(diagnostic -> diagnostic.toString())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private record Compilation(Path generated, Path classes) {}
}
