package com.reactor.rust.codegen;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static com.reactor.rust.codegen.ComponentGraphScanner.ComponentModel;
import static com.reactor.rust.codegen.HttpClientStubEmitter.HttpClientModel;

/** Generates startup indexes, property metadata and an application component factory. */
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedOptions({
        "reactor.codegen.descriptorPackage",
        "reactor.codegen.descriptorName",
        "reactor.codegen.handlers",
        "reactor.codegen.dispatch",
        "reactor.codegen.excludePackages",
        "reactor.codegen.excludeClasses"
        , "reactor.application.version"
})
public final class ReactorStartupProcessor extends AbstractProcessor {

    static final String COMPONENT = "com.reactor.rust.di.annotation.Component";
    static final String PRIMARY = "com.reactor.rust.di.annotation.Primary";
    static final String CONFIGURATION = "com.reactor.rust.di.annotation.Configuration";
    static final String SERVICE = "com.reactor.rust.di.annotation.Service";
    static final String REPOSITORY = "com.reactor.rust.di.annotation.Repository";
    static final String BEAN = "com.reactor.rust.di.annotation.Bean";
    static final String AUTOWIRED = "com.reactor.rust.di.annotation.Autowired";
    static final String POST_CONSTRUCT = "com.reactor.rust.di.annotation.PostConstruct";
    static final String PRE_DESTROY = "com.reactor.rust.di.annotation.PreDestroy";
    static final String SCHEDULED = "com.reactor.rust.annotations.Scheduled";
    static final String HTTP_CLIENT = "com.reactor.rust.http.client.ReactorHttpClient";
    static final String HTTP_EXCHANGE = "com.reactor.rust.http.client.HttpExchange";
    static final String HTTP_CIRCUIT_BREAKER = "com.reactor.rust.http.client.HttpCircuitBreaker";
    static final String RUST_PROPERTY = "com.reactor.rust.annotations.RustProperty";
    static final String CONFIGURATION_PROPERTIES =
            "com.reactor.rust.annotations.ConfigurationProperties";
    static final String CONFIG_DEFAULT = "com.reactor.rust.annotations.ConfigDefault";
    static final String CONFIG_NAME = "com.reactor.rust.annotations.ConfigName";
    static final String REQUIRES_PROPERTY = "com.reactor.rust.annotations.RequiresProperty";
    static final String REQUIRES_PROPERTIES = "com.reactor.rust.annotations.RequiresProperties";
    static final String PROFILE = "com.reactor.rust.annotations.Profile";
    static final String EXCEPTION_HANDLER = "com.reactor.rust.exception.ExceptionHandler";
    static final String REQUEST_PARAM = "com.reactor.rust.annotations.RequestParam";
    static final String PATH_VARIABLE = "com.reactor.rust.annotations.PathVariable";
    static final String REQUEST_BODY = "com.reactor.rust.annotations.RequestBody";
    static final String HEADER_PARAM = "com.reactor.rust.annotations.HeaderParam";
    static final String COOKIE_VALUE = "com.reactor.rust.annotations.CookieValue";
    static final String REQUEST = "com.reactor.rust.annotations.Request";
    static final String RESPONSE = "com.reactor.rust.annotations.Response";
    static final String FIELD = "com.reactor.rust.annotations.Field";
    static final String NOT_NULL = "com.reactor.rust.annotations.NotNull";
    static final String NOT_BLANK = "com.reactor.rust.annotations.NotBlank";
    static final String NOT_EMPTY = "com.reactor.rust.annotations.NotEmpty";
    static final String SIZE = "com.reactor.rust.annotations.Size";
    static final String EMAIL = "com.reactor.rust.annotations.Email";
    static final String PATTERN = "com.reactor.rust.annotations.Pattern";
    static final String MIN = "com.reactor.rust.annotations.Min";
    static final String MAX = "com.reactor.rust.annotations.Max";
    static final String POSITIVE = "com.reactor.rust.annotations.Positive";
    static final String NEGATIVE = "com.reactor.rust.annotations.Negative";
    static final String DECIMAL_MIN = "com.reactor.rust.annotations.DecimalMin";
    static final String DECIMAL_MAX = "com.reactor.rust.annotations.DecimalMax";
    static final String REQUEST_MAPPING = "com.reactor.rust.annotations.RequestMapping";
    static final String REST_CONTROLLER = "com.reactor.rust.annotations.RestController";
    static final String RUST_ROUTE = "com.reactor.rust.annotations.RustRoute";
    static final String MAX_REQUEST_BODY_SIZE = "com.reactor.rust.annotations.MaxRequestBodySize";
    static final String MAX_RESPONSE_SIZE = "com.reactor.rust.annotations.MaxResponseSize";
    static final String OPENAPI_OPERATION = "com.reactor.rust.openapi.Operation";
    static final String OPENAPI_RESPONSE = "com.reactor.rust.openapi.ApiResponse";
    static final String OPENAPI_RESPONSES = "com.reactor.rust.openapi.ApiResponses";
    static final String REACTOR_APPLICATION = "com.reactor.rust.annotations.ReactorApplication";
    static final String LEGACY_MIDDLEWARE = "com.reactor.rust.middleware.Middleware";
    static final String DESCRIPTOR_SERVICE =
            "META-INF/services/com.reactor.rust.startup.ApplicationDescriptor";
    static final Map<String, String> MAPPINGS = Map.of(
            "com.reactor.rust.annotations.GetMapping", "GET",
            "com.reactor.rust.annotations.PostMapping", "POST",
            "com.reactor.rust.annotations.PutMapping", "PUT",
            "com.reactor.rust.annotations.DeleteMapping", "DELETE",
            "com.reactor.rust.annotations.PatchMapping", "PATCH");


    final ComponentGraphScanner graph = new ComponentGraphScanner(this);
    final RouteModelCollector routes = new RouteModelCollector(this);
    final SwitchDispatchEmitter dispatch = new SwitchDispatchEmitter(this);
    final ValidationCodeEmitter validation = new ValidationCodeEmitter(this);
    final HttpClientStubEmitter http = new HttpClientStubEmitter(this);
    final PropertyMetadataEmitter properties = new PropertyMetadataEmitter(this);

    boolean generated;
    boolean externalHandlersCollected;

    javax.annotation.processing.ProcessingEnvironment environment() {
        return processingEnv;
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of("*");
    }


    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        dispatch.configure();
        if (!dispatch.dispatchValid) return false;
        if (!roundEnv.processingOver()) {
            collectExternalHandlers();
            for (Element root : roundEnv.getRootElements()) {
                collect(root);
            }
            graph.retryDeferredComponents();
            graph.retryDeferredConfigurations();
            return false;
        }
        if (!generated && (routes.applicationType != null || !graph.components.isEmpty() || !http.httpClients.isEmpty() || !routes.routes.isEmpty()
                || !properties.properties.isEmpty() || !validation.validationTypes.isEmpty())) {
            generated = true;
            generateArtifacts();
        }
        return false;
    }

    void collectExternalHandlers() {
        if (externalHandlersCollected) {
            return;
        }
        externalHandlersCollected = true;
        String configured = processingEnv.getOptions().getOrDefault("reactor.codegen.handlers", "");
        for (String className : configured.split(",")) {
            String normalized = className.trim();
            if (normalized.isEmpty()) {
                continue;
            }
            TypeElement type = processingEnv.getElementUtils().getTypeElement(normalized);
            if (type == null) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "Configured reactor.codegen.handlers type cannot be resolved: " + normalized);
            } else {
                graph.collectType(type);
            }
        }
    }

    void collect(Element element) {
        if (element instanceof TypeElement type) {
            graph.collectType(type);
        }
        for (Element enclosed : element.getEnclosedElements()) {
            if (enclosed.getKind().isClass() || enclosed.getKind().isInterface()) {
                collect(enclosed);
            }
        }
    }

    void rejectLegacyMiddleware(TypeElement type) {
        TypeElement middleware = processingEnv.getElementUtils().getTypeElement(LEGACY_MIDDLEWARE);
        if (middleware == null || type.getQualifiedName().contentEquals(LEGACY_MIDDLEWARE)) return;
        if (processingEnv.getTypeUtils().isAssignable(type.asType(), middleware.asType())) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Legacy Middleware is not connected to the native request path. "
                            + "Use a RequestGuardFactory so route selection remains build-time and allocation-free.",
                    type);
        }
    }

    boolean excluded(TypeElement type) {
        String className = type.getQualifiedName().toString();
        for (String excluded : optionValues("reactor.codegen.excludeClasses")) {
            if (className.equals(excluded)) {
                return true;
            }
        }
        for (String excluded : optionValues("reactor.codegen.excludePackages")) {
            if (className.equals(excluded) || className.startsWith(excluded + ".")) {
                return true;
            }
        }
        return false;
    }

    List<String> optionValues(String key) {
        String configured = processingEnv.getOptions().getOrDefault(key, "");
        List<String> values = new ArrayList<>();
        for (String value : configured.split(",")) {
            String normalized = value.trim();
            if (!normalized.isEmpty() && !"__none__".equals(normalized)) {
                values.add(normalized);
            }
        }
        return values;
    }

    void generateArtifacts() {
        if (dispatch.switchDispatch) {
            if (!dispatch.prepareSwitchDispatch()) return;
            dispatch.generateSwitchDispatch();
        }
        Map<String, String> generatedValidators = new TreeMap<>();
        for (TypeElement validationType : validation.validationTypes.values()) {
            String generatedType = validation.generateValidator(validationType);
            if (generatedType != null) {
                generatedValidators.put(validationType.getQualifiedName().toString(), generatedType);
            }
        }
        for (ComponentModel component : graph.components.values()) {
            graph.generateComponentFactory(component);
        }
        for (HttpClientModel client : http.httpClients.values()) {
            http.generateHttpClient(client);
        }
        writeResource("META-INF/reactor/components.idx", graph.allComponentTypes());
        writeResource("META-INF/reactor/routes.idx", routes.routes.values());
        writeResource("META-INF/reactor/properties.idx", properties.properties);
        properties.writeConfigurationMetadata();
        routes.writeOpenApiDocument();

        graph.generateDescriptor(generatedValidators);
    }

    static String stableSuffix(Iterable<String> values) {
        long hash = 0xcbf29ce484222325L;
        for (String value : values) {
            for (int index = 0; index < value.length(); index++) {
                hash ^= value.charAt(index);
                hash *= 0x100000001b3L;
            }
            hash ^= '\n';
            hash *= 0x100000001b3L;
        }
        return Long.toUnsignedString(hash, 16);
    }

    int annotationInt(AnnotationMirror mirror, String key, int fallback) {
        Object value = annotationValue(mirror, key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    long annotationLong(AnnotationMirror mirror, String key, long fallback) {
        Object value = annotationValue(mirror, key);
        return value instanceof Number number ? number.longValue() : fallback;
    }

    double annotationDouble(AnnotationMirror mirror, String key, double fallback) {
        Object value = annotationValue(mirror, key);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    void writeListMethod(Writer writer, String name, List<String> values) throws IOException {
        writer.write("    @Override\n");
        writer.write("    public java.util.List<String> " + name + "() {\n");
        if (values.isEmpty()) {
            writer.write("        return java.util.List.of();\n");
        } else {
            writer.write("        return java.util.List.of(\n");
            for (int index = 0; index < values.size(); index++) {
                writer.write("                \"" + escape(values.get(index)) + "\""
                        + (index + 1 == values.size() ? "\n" : ",\n"));
            }
            writer.write("        );\n");
        }
        writer.write("    }\n\n");
    }

    void writeResource(String name, Iterable<String> lines) {
        try {
            FileObject resource = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", name);
            try (Writer writer = resource.openWriter()) {
                for (String line : lines) {
                    writer.write(line);
                    writer.write('\n');
                }
            }
        } catch (IOException failure) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Failed to generate " + name + ": " + failure.getMessage());
        }
    }

    boolean isVoidType(TypeMirror type) {
        if (type == null || type.getKind() == TypeKind.VOID) {
            return true;
        }
        return processingEnv.getTypeUtils().erasure(type).toString().equals("java.lang.Void");
    }

    boolean isCompletionStage(TypeMirror returnType) {
        TypeElement completionStage = processingEnv.getElementUtils()
                .getTypeElement("java.util.concurrent.CompletionStage");
        return completionStage != null && processingEnv.getTypeUtils().isAssignable(
                processingEnv.getTypeUtils().erasure(returnType),
                processingEnv.getTypeUtils().erasure(completionStage.asType()));
    }

    static String methodSignature(ExecutableElement method) {
        StringBuilder signature = new StringBuilder(method.getSimpleName()).append('(');
        for (VariableElement parameter : method.getParameters()) {
            signature.append(parameter.asType()).append(';');
        }
        return signature.append(')').toString();
    }

    boolean hasAnnotation(Element element, String qualifiedName) {
        return annotation(element, qualifiedName) != null;
    }

    AnnotationMirror annotation(Element element, String qualifiedName) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (mirror.getAnnotationType().toString().equals(qualifiedName)) {
                return mirror;
            }
        }
        return null;
    }

    String annotationString(Element element, String annotationName, String key, String fallback) {
        AnnotationMirror mirror = annotation(element, annotationName);
        return mirror == null ? fallback : annotationString(mirror, key, fallback);
    }

    String annotationString(AnnotationMirror mirror, String key, String fallback) {
        Map<? extends ExecutableElement, ? extends AnnotationValue> values =
                processingEnv.getElementUtils().getElementValuesWithDefaults(mirror);
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : values.entrySet()) {
            if (entry.getKey().getSimpleName().contentEquals(key)) {
                Object value = entry.getValue().getValue();
                return value == null ? fallback : value.toString();
            }
        }
        return fallback;
    }

    boolean annotationBoolean(
            AnnotationMirror mirror,
            String key,
            boolean fallback) {
        Object value = annotationValue(mirror, key);
        return value instanceof Boolean bool ? bool : fallback;
    }

    List<String> annotationStrings(AnnotationMirror mirror, String key) {
        Object value = annotationValue(mirror, key);
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<String> result = new ArrayList<>(values.size());
        for (Object item : values) {
            if (item instanceof AnnotationValue annotationValue) {
                result.add(String.valueOf(annotationValue.getValue()));
            }
        }
        return result;
    }

    List<AnnotationMirror> annotationMirrors(AnnotationMirror mirror, String key) {
        Object value = annotationValue(mirror, key);
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<AnnotationMirror> result = new ArrayList<>(values.size());
        for (Object item : values) {
            if (item instanceof AnnotationValue annotationValue
                    && annotationValue.getValue() instanceof AnnotationMirror nested) {
                result.add(nested);
            }
        }
        return result;
    }

    List<TypeMirror> annotationTypes(AnnotationMirror mirror, String key) {
        Object value = annotationValue(mirror, key);
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<TypeMirror> result = new ArrayList<>(values.size());
        for (Object item : values) {
            if (item instanceof AnnotationValue annotationValue
                    && annotationValue.getValue() instanceof TypeMirror type) {
                result.add(type);
            }
        }
        return result;
    }

    TypeMirror annotationType(AnnotationMirror mirror, String key) {
        Object value = annotationValue(mirror, key);
        return value instanceof TypeMirror type ? type : null;
    }

    Object annotationValue(AnnotationMirror mirror, String key) {
        Map<? extends ExecutableElement, ? extends AnnotationValue> values =
                processingEnv.getElementUtils().getElementValuesWithDefaults(mirror);
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : values.entrySet()) {
            if (entry.getKey().getSimpleName().contentEquals(key)) {
                return entry.getValue().getValue();
            }
        }
        return null;
    }

    static String combine(String basePath, String methodPath) {
        String base = normalizePath(basePath, false);
        String method = normalizePath(methodPath, true);
        if (base.isEmpty()) {
            return method;
        }
        return "/".equals(method) ? base : base + method;
    }

    static String normalizePath(String value, boolean rootWhenEmpty) {
        if (value == null || value.isBlank()) {
            return rootWhenEmpty ? "/" : "";
        }
        String normalized = value.startsWith("/") ? value : "/" + value;
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    static String beanName(String simpleName) {
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }

    static String kebabCase(String value) {
        StringBuilder result = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isUpperCase(character)) {
                if (index > 0) result.append('-');
                result.append(Character.toLowerCase(character));
            } else {
                result.append(character);
            }
        }
        return result.toString();
    }

    static String javaString(String value) {
        return value == null ? "null" : "\"" + escape(value) + "\"";
    }

    static String jsonEscape(String value) {
        return value == null ? "" : escape(value);
    }

    static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

}
