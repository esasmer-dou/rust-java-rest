package com.reactor.rust.codegen;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Generates startup indexes, property metadata and an application component factory. */
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedOptions({
        "reactor.codegen.descriptorPackage",
        "reactor.codegen.descriptorName",
        "reactor.codegen.handlers",
        "reactor.codegen.excludePackages",
        "reactor.codegen.excludeClasses"
})
public final class ReactorStartupProcessor extends AbstractProcessor {

    private static final String COMPONENT = "com.reactor.rust.di.annotation.Component";
    private static final String PRIMARY = "com.reactor.rust.di.annotation.Primary";
    private static final String CONFIGURATION = "com.reactor.rust.di.annotation.Configuration";
    private static final String SERVICE = "com.reactor.rust.di.annotation.Service";
    private static final String REPOSITORY = "com.reactor.rust.di.annotation.Repository";
    private static final String BEAN = "com.reactor.rust.di.annotation.Bean";
    private static final String AUTOWIRED = "com.reactor.rust.di.annotation.Autowired";
    private static final String POST_CONSTRUCT = "com.reactor.rust.di.annotation.PostConstruct";
    private static final String PRE_DESTROY = "com.reactor.rust.di.annotation.PreDestroy";
    private static final String SCHEDULED = "com.reactor.rust.annotations.Scheduled";
    private static final String HTTP_CLIENT = "com.reactor.rust.http.client.ReactorHttpClient";
    private static final String HTTP_EXCHANGE = "com.reactor.rust.http.client.HttpExchange";
    private static final String RUST_PROPERTY = "com.reactor.rust.annotations.RustProperty";
    private static final String CONFIGURATION_PROPERTIES =
            "com.reactor.rust.annotations.ConfigurationProperties";
    private static final String CONFIG_DEFAULT = "com.reactor.rust.annotations.ConfigDefault";
    private static final String CONFIG_NAME = "com.reactor.rust.annotations.ConfigName";
    private static final String REQUIRES_PROPERTY = "com.reactor.rust.annotations.RequiresProperty";
    private static final String REQUIRES_PROPERTIES = "com.reactor.rust.annotations.RequiresProperties";
    private static final String PROFILE = "com.reactor.rust.annotations.Profile";
    private static final String EXCEPTION_HANDLER = "com.reactor.rust.exception.ExceptionHandler";
    private static final String REQUEST_PARAM = "com.reactor.rust.annotations.RequestParam";
    private static final String PATH_VARIABLE = "com.reactor.rust.annotations.PathVariable";
    private static final String REQUEST_BODY = "com.reactor.rust.annotations.RequestBody";
    private static final String HEADER_PARAM = "com.reactor.rust.annotations.HeaderParam";
    private static final String COOKIE_VALUE = "com.reactor.rust.annotations.CookieValue";
    private static final String REQUEST = "com.reactor.rust.annotations.Request";
    private static final String RESPONSE = "com.reactor.rust.annotations.Response";
    private static final String FIELD = "com.reactor.rust.annotations.Field";
    private static final String NOT_NULL = "com.reactor.rust.annotations.NotNull";
    private static final String NOT_BLANK = "com.reactor.rust.annotations.NotBlank";
    private static final String NOT_EMPTY = "com.reactor.rust.annotations.NotEmpty";
    private static final String SIZE = "com.reactor.rust.annotations.Size";
    private static final String EMAIL = "com.reactor.rust.annotations.Email";
    private static final String PATTERN = "com.reactor.rust.annotations.Pattern";
    private static final String MIN = "com.reactor.rust.annotations.Min";
    private static final String MAX = "com.reactor.rust.annotations.Max";
    private static final String POSITIVE = "com.reactor.rust.annotations.Positive";
    private static final String NEGATIVE = "com.reactor.rust.annotations.Negative";
    private static final String DECIMAL_MIN = "com.reactor.rust.annotations.DecimalMin";
    private static final String DECIMAL_MAX = "com.reactor.rust.annotations.DecimalMax";
    private static final String REQUEST_MAPPING = "com.reactor.rust.annotations.RequestMapping";
    private static final String REST_CONTROLLER = "com.reactor.rust.annotations.RestController";
    private static final String RUST_ROUTE = "com.reactor.rust.annotations.RustRoute";
    private static final String MAX_REQUEST_BODY_SIZE = "com.reactor.rust.annotations.MaxRequestBodySize";
    private static final String MAX_RESPONSE_SIZE = "com.reactor.rust.annotations.MaxResponseSize";
    private static final String OPENAPI_OPERATION = "com.reactor.rust.openapi.Operation";
    private static final String OPENAPI_RESPONSE = "com.reactor.rust.openapi.ApiResponse";
    private static final String OPENAPI_RESPONSES = "com.reactor.rust.openapi.ApiResponses";
    private static final String REACTOR_APPLICATION = "com.reactor.rust.annotations.ReactorApplication";
    private static final String LEGACY_MIDDLEWARE = "com.reactor.rust.middleware.Middleware";
    private static final String DESCRIPTOR_SERVICE =
            "META-INF/services/com.reactor.rust.startup.ApplicationDescriptor";
    private static final Map<String, String> MAPPINGS = Map.of(
            "com.reactor.rust.annotations.GetMapping", "GET",
            "com.reactor.rust.annotations.PostMapping", "POST",
            "com.reactor.rust.annotations.PutMapping", "PUT",
            "com.reactor.rust.annotations.DeleteMapping", "DELETE",
            "com.reactor.rust.annotations.PatchMapping", "PATCH");

    private final Map<String, ComponentModel> components = new TreeMap<>();
    private final Map<String, ConfigurationRecordModel> configurationRecords = new TreeMap<>();
    private final Map<String, ConditionsModel> componentConditions = new TreeMap<>();
    private final Set<String> deferredComponents = new TreeSet<>();
    private final Map<String, String> routes = new TreeMap<>();
    private final Map<String, OpenApiRouteModel> openApiRoutes = new TreeMap<>();
    private final Set<String> routeOwners = new TreeSet<>();
    private final Map<String, Map<String, RouteMethodModel>> routeMethods = new TreeMap<>();
    private final Map<String, Map<String, ExceptionHandlerMethodModel>> exceptionHandlerMethods =
            new TreeMap<>();
    private final Map<String, List<ScheduledMethodModel>> scheduledMethods = new TreeMap<>();
    private final Map<String, HttpClientModel> httpClients = new TreeMap<>();
    private final Map<String, List<BeanMethodModel>> configurationMethods = new TreeMap<>();
    private final Map<String, TypeElement> validationTypes = new TreeMap<>();
    private final Set<String> deferredConfigurations = new TreeSet<>();
    private final Set<String> properties = new TreeSet<>();
    private String applicationType;
    private String openApiTitle = "Reactor Application";
    private String openApiVersion = "1.0.0";
    private String openApiDescription = "";
    private boolean generated;
    private boolean externalHandlersCollected;

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of("*");
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (!roundEnv.processingOver()) {
            collectExternalHandlers();
            for (Element root : roundEnv.getRootElements()) {
                collect(root);
            }
            retryDeferredComponents();
            retryDeferredConfigurations();
            return false;
        }
        if (!generated && (applicationType != null || !components.isEmpty() || !httpClients.isEmpty() || !routes.isEmpty()
                || !properties.isEmpty() || !validationTypes.isEmpty())) {
            generated = true;
            generateArtifacts();
        }
        return false;
    }

    private void collectExternalHandlers() {
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
                collectType(type);
            }
        }
    }

    private void collect(Element element) {
        if (element instanceof TypeElement type) {
            collectType(type);
        }
        for (Element enclosed : element.getEnclosedElements()) {
            if (enclosed.getKind().isClass() || enclosed.getKind().isInterface()) {
                collect(enclosed);
            }
        }
    }

    private void collectType(TypeElement type) {
        if (excluded(type)) {
            return;
        }
        collectApplicationMetadata(type);
        rejectLegacyMiddleware(type);
        if (hasAnnotation(type, REQUEST) || hasAnnotation(type, RESPONSE)) {
            if (type.getKind() != ElementKind.RECORD) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "@Request and @Response types must be records",
                        type);
            } else {
                validationTypes.put(type.getQualifiedName().toString(), type);
            }
        }
        if (hasAnnotation(type, HTTP_CLIENT)) {
            collectHttpClient(type);
        }
        AnnotationMirror configurationProperties = annotation(type, CONFIGURATION_PROPERTIES);
        ComponentAnnotation component = configurationProperties == null
                ? componentAnnotation(type)
                : new ComponentAnnotation("");
        if (component != null && type.getNestingKind().isNested() == false) {
            String qualifiedName = type.getQualifiedName().toString();
            ComponentModel model;
            if (configurationProperties != null) {
                ConfigurationRecordModel configuration = configurationRecordModel(type, configurationProperties);
                configurationRecords.put(qualifiedName, configuration);
                model = componentModel(type, component, List.of());
            } else {
                model = componentModel(type, component);
            }
            componentConditions.put(qualifiedName, conditions(type));
            if (configurationProperties == null && hasUnresolvedConstructorDependency(type)) {
                components.putIfAbsent(qualifiedName, model);
                deferredComponents.add(qualifiedName);
            } else {
                components.put(qualifiedName, model);
                deferredComponents.remove(qualifiedName);
            }
            collectBeanMethods(type);
        }

        String basePath = controllerBasePath(type);
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.FIELD
                    || enclosed.getKind() == ElementKind.RECORD_COMPONENT) {
                collectProperty(type, enclosed);
            }
            if (enclosed instanceof ExecutableElement method && enclosed.getKind() == ElementKind.METHOD) {
                collectExceptionHandler(type, method);
                collectRoute(type, method, basePath);
                collectScheduledMethod(type, method);
            }
        }
    }

    private void collectApplicationMetadata(TypeElement type) {
        AnnotationMirror application = annotation(type, REACTOR_APPLICATION);
        if (application == null) return;
        String qualifiedName = type.getQualifiedName().toString();
        if (applicationType != null && !applicationType.equals(qualifiedName)) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Only one @ReactorApplication entry point may be compiled per application module",
                    type);
            return;
        }
        applicationType = qualifiedName;
        String configuredName = annotationString(application, "name", "").trim();
        openApiTitle = configuredName.isEmpty() ? type.getSimpleName().toString() : configuredName;
        String configuredVersion = annotationString(application, "version", "1.0.0").trim();
        openApiVersion = configuredVersion.isEmpty() ? "1.0.0" : configuredVersion;
        openApiDescription = annotationString(application, "description", "").trim();
    }

    private void rejectLegacyMiddleware(TypeElement type) {
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

    private void collectHttpClient(TypeElement type) {
        if (type.getKind() != ElementKind.INTERFACE || type.getNestingKind().isNested()) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@ReactorHttpClient must target a top-level interface",
                    type);
            return;
        }
        AnnotationMirror client = annotation(type, HTTP_CLIENT);
        String baseUrlProperty = annotationString(client, "baseUrlProperty", "").trim();
        if (baseUrlProperty.isEmpty()) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@ReactorHttpClient baseUrlProperty must not be blank",
                    type);
            return;
        }
        ArrayList<HttpClientMethodModel> methods = new ArrayList<>();
        for (Element enclosed : type.getEnclosedElements()) {
            if (!(enclosed instanceof ExecutableElement method)
                    || enclosed.getKind() != ElementKind.METHOD) continue;
            AnnotationMirror exchange = annotation(method, HTTP_EXCHANGE);
            if (exchange == null) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "Every abstract @ReactorHttpClient method requires @HttpExchange",
                        method);
                continue;
            }
            HttpClientMethodModel model = httpClientMethod(type, method, exchange);
            if (model != null) methods.add(model);
        }
        if (methods.isEmpty()) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@ReactorHttpClient must declare at least one valid @HttpExchange method",
                    type);
            return;
        }
        String packageName = processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();
        String simpleName = type.getSimpleName().toString();
        String generatedName = simpleName + "__ReactorHttpClient";
        String beanName = annotationString(client, "name", "").trim();
        if (beanName.isEmpty()) beanName = beanName(simpleName);
        httpClients.put(type.getQualifiedName().toString(), new HttpClientModel(
                type.getQualifiedName().toString(),
                packageName,
                generatedName,
                packageName.isEmpty() ? generatedName : packageName + '.' + generatedName,
                beanName,
                baseUrlProperty,
                List.copyOf(methods),
                type));
        properties.add(baseUrlProperty + "\tjava.lang.String\t\t" + type.getQualifiedName());
    }

    private HttpClientMethodModel httpClientMethod(
            TypeElement owner,
            ExecutableElement method,
            AnnotationMirror exchange) {
        if (method.getModifiers().contains(Modifier.STATIC)
                || method.getModifiers().contains(Modifier.PRIVATE)
                || method.getModifiers().contains(Modifier.DEFAULT)
                || !method.getTypeParameters().isEmpty()) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Generated HTTP client methods must be non-static abstract methods without type parameters",
                    method);
            return null;
        }
        if (!(method.getReturnType() instanceof DeclaredType returnType)
                || !"java.util.concurrent.CompletionStage".equals(
                processingEnv.getTypeUtils().erasure(returnType).toString())
                || returnType.getTypeArguments().size() != 1) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Generated HTTP client methods must return CompletionStage<T>",
                    method);
            return null;
        }
        TypeMirror response = returnType.getTypeArguments().getFirst();
        boolean responseWrapper = false;
        if (response instanceof DeclaredType declared
                && "com.reactor.rust.http.client.HttpClientResponse".equals(
                processingEnv.getTypeUtils().erasure(declared).toString())) {
            if (declared.getTypeArguments().size() != 1) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "HttpClientResponse requires one body type",
                        method);
                return null;
            }
            response = declared.getTypeArguments().getFirst();
            responseWrapper = true;
        }
        String responseKind = "CLASS";
        String responseType = response.toString();
        if (response instanceof DeclaredType declared && !declared.getTypeArguments().isEmpty()) {
            if ("java.util.List".equals(processingEnv.getTypeUtils().erasure(declared).toString())
                    && declared.getTypeArguments().size() == 1
                    && declared.getTypeArguments().getFirst() instanceof DeclaredType element
                    && element.getTypeArguments().isEmpty()) {
                responseKind = "LIST";
                responseType = element.toString();
            } else {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "Only List<Record> is supported as a parameterized outbound response body; "
                                + "use a record wrapper for other generic shapes",
                        method);
                return null;
            }
        }
        if (!(response.getKind() == TypeKind.ARRAY || response instanceof DeclaredType)) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Unsupported outbound response type " + response,
                    method);
            return null;
        }
        String httpMethod = String.valueOf(annotationValue(exchange, "method"));
        String path = annotationString(exchange, "path", "").trim();
        if (path.isEmpty()) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "@HttpExchange path must not be blank", method);
            return null;
        }
        long timeoutMs = annotationLong(exchange, "timeoutMs", 0L);
        int retries = annotationInt(exchange, "retries", -1);
        if (timeoutMs < 0L || retries < -1 || retries > 3) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@HttpExchange timeoutMs must be >= 0 and retries must be between -1 and 3",
                    method);
            return null;
        }
        ArrayList<HttpClientParameterModel> parameters = new ArrayList<>();
        boolean requestBody = false;
        int parameterIndex = 0;
        for (VariableElement parameter : method.getParameters()) {
            HttpClientParameterModel binding = httpClientParameter(parameter, parameterIndex++);
            if (binding == null) return null;
            if (binding.kind().equals("BODY")) {
                if (requestBody) {
                    processingEnv.getMessager().printMessage(
                            Diagnostic.Kind.ERROR,
                            "Only one @RequestBody is supported on an outbound HTTP method",
                            parameter);
                    return null;
                }
                requestBody = true;
            }
            if (binding.kind().equals("PATH") && !path.contains("{" + binding.name() + "}")) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "Outbound path does not contain {" + binding.name() + "}",
                        parameter);
                return null;
            }
            parameters.add(binding);
        }
        if (requestBody && ("GET".equals(httpMethod) || "DELETE".equals(httpMethod))) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "GET and DELETE outbound exchanges must not declare @RequestBody",
                    method);
            return null;
        }
        return new HttpClientMethodModel(
                method.getSimpleName().toString(),
                method.getReturnType().toString(),
                responseType,
                responseKind,
                httpMethod,
                path,
                annotationString(exchange, "contentType", "application/json; charset=utf-8"),
                annotationString(exchange, "accept", "application/json"),
                timeoutMs,
                retries,
                annotationBoolean(exchange, "idempotent", false),
                responseWrapper,
                List.copyOf(parameters));
    }

    private HttpClientParameterModel httpClientParameter(VariableElement parameter, int index) {
        AnnotationMirror path = annotation(parameter, PATH_VARIABLE);
        AnnotationMirror query = annotation(parameter, REQUEST_PARAM);
        AnnotationMirror header = annotation(parameter, HEADER_PARAM);
        AnnotationMirror body = annotation(parameter, REQUEST_BODY);
        int count = (path == null ? 0 : 1) + (query == null ? 0 : 1)
                + (header == null ? 0 : 1) + (body == null ? 0 : 1);
        if (count != 1) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Each outbound HTTP parameter requires exactly one of @PathVariable, @RequestParam, "
                            + "@HeaderParam, or @RequestBody",
                    parameter);
            return null;
        }
        boolean optional = parameter.asType() instanceof DeclaredType declared
                && "java.util.Optional".equals(processingEnv.getTypeUtils().erasure(declared).toString());
        if (optional && (path != null || body != null)) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Optional is supported only for outbound query and header parameters",
                    parameter);
            return null;
        }
        if (path != null) return new HttpClientParameterModel(
                parameter.asType().toString(), index, "PATH", annotationString(path, "value", ""), false);
        if (query != null) return new HttpClientParameterModel(
                parameter.asType().toString(), index, "QUERY", annotationString(query, "value", ""), optional);
        if (header != null) return new HttpClientParameterModel(
                parameter.asType().toString(), index, "HEADER", annotationString(header, "value", ""), optional);
        return new HttpClientParameterModel(parameter.asType().toString(), index, "BODY", "", false);
    }

    private void collectScheduledMethod(TypeElement owner, ExecutableElement method) {
        AnnotationMirror scheduled = annotation(method, SCHEDULED);
        if (scheduled == null) return;
        if (componentAnnotation(owner) == null) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@Scheduled methods must belong to a generated component",
                    method);
            return;
        }
        boolean valid = method.getParameters().isEmpty()
                && method.getReturnType().getKind() == TypeKind.VOID
                && !method.getModifiers().contains(Modifier.PRIVATE)
                && !method.getModifiers().contains(Modifier.STATIC);
        if (!valid) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@Scheduled methods must be non-private instance void methods with no parameters",
                    method);
            return;
        }
        long intervalMs = annotationLong(scheduled, "intervalMs", -1L);
        String intervalProperty = annotationString(scheduled, "intervalProperty", "").trim();
        if ((intervalMs > 0L) == !intervalProperty.isEmpty()) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@Scheduled requires exactly one of intervalMs or intervalProperty",
                    method);
            return;
        }
        long initialDelayMs = annotationLong(scheduled, "initialDelayMs", 0L);
        if (initialDelayMs < 0L) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@Scheduled initialDelayMs must be >= 0",
                    method);
            return;
        }
        String initialDelayProperty = annotationString(scheduled, "initialDelayProperty", "").trim();
        long lockAtMostMs = annotationLong(scheduled, "lockAtMostMs", 60_000L);
        if (lockAtMostMs <= 0L) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@Scheduled lockAtMostMs must be > 0",
                    method);
            return;
        }
        String lockAtMostProperty = annotationString(scheduled, "lockAtMostProperty", "").trim();
        String name = annotationString(scheduled, "name", "").trim();
        if (name.isEmpty()) name = owner.getQualifiedName() + "#" + method.getSimpleName();
        String mode = String.valueOf(annotationValue(scheduled, "mode"));
        ScheduledMethodModel model = new ScheduledMethodModel(
                method.getSimpleName().toString(),
                name,
                mode,
                intervalMs,
                intervalProperty,
                initialDelayMs,
                initialDelayProperty,
                annotationString(scheduled, "lockName", "").trim(),
                lockAtMostMs,
                lockAtMostProperty);
        scheduledMethods.computeIfAbsent(owner.getQualifiedName().toString(), ignored -> new ArrayList<>())
                .add(model);
        if (!intervalProperty.isEmpty()) {
            properties.add(intervalProperty + "\tlong\t\t" + owner.getQualifiedName() + "#" + method.getSimpleName());
        }
        if (!initialDelayProperty.isEmpty()) {
            properties.add(initialDelayProperty + "\tlong\t" + initialDelayMs + "\t"
                    + owner.getQualifiedName() + "#" + method.getSimpleName());
        }
        if (!lockAtMostProperty.isEmpty()) {
            properties.add(lockAtMostProperty + "\tlong\t" + lockAtMostMs + "\t"
                    + owner.getQualifiedName() + "#" + method.getSimpleName());
        }
    }

    private void collectExceptionHandler(TypeElement owner, ExecutableElement method) {
        AnnotationMirror handler = annotation(method, EXCEPTION_HANDLER);
        if (handler == null) return;
        if (method.getModifiers().contains(Modifier.PRIVATE)
                || method.getModifiers().contains(Modifier.STATIC)) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Generated exception handlers must be non-private instance methods",
                    method);
            return;
        }
        if (method.getParameters().size() > 1) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@ExceptionHandler methods accept zero or one Throwable parameter",
                    method);
            return;
        }

        TypeMirror throwable = processingEnv.getElementUtils()
                .getTypeElement(Throwable.class.getName()).asType();
        String parameterType = null;
        if (!method.getParameters().isEmpty()) {
            TypeMirror parameter = method.getParameters().get(0).asType();
            if (!processingEnv.getTypeUtils().isAssignable(parameter, throwable)) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "@ExceptionHandler parameter must extend Throwable",
                        method);
                return;
            }
            parameterType = parameter.toString();
        }

        List<TypeMirror> declaredTypes = annotationTypes(handler, "value");
        List<String> exceptionTypes = new ArrayList<>();
        if (declaredTypes.isEmpty()) {
            exceptionTypes.add(parameterType == null ? Throwable.class.getName() : parameterType);
        } else {
            TypeMirror parameter = method.getParameters().isEmpty()
                    ? null
                    : method.getParameters().get(0).asType();
            for (TypeMirror declaredType : declaredTypes) {
                if (!processingEnv.getTypeUtils().isAssignable(declaredType, throwable)) {
                    processingEnv.getMessager().printMessage(
                            Diagnostic.Kind.ERROR,
                            "@ExceptionHandler value must extend Throwable: " + declaredType,
                            method);
                    continue;
                }
                if (parameter != null && !processingEnv.getTypeUtils().isAssignable(declaredType, parameter)) {
                    processingEnv.getMessager().printMessage(
                            Diagnostic.Kind.ERROR,
                            "Exception " + declaredType + " cannot be passed to handler parameter " + parameter,
                            method);
                    continue;
                }
                exceptionTypes.add(declaredType.toString());
            }
        }
        if (exceptionTypes.isEmpty()) return;
        ExceptionHandlerMethodModel model = new ExceptionHandlerMethodModel(
                method.getSimpleName().toString(),
                parameterType,
                List.copyOf(exceptionTypes),
                method.getReturnType().getKind() == TypeKind.VOID);
        exceptionHandlerMethods.computeIfAbsent(owner.getQualifiedName().toString(), ignored -> new TreeMap<>())
                .putIfAbsent(methodSignature(method), model);
    }

    private boolean excluded(TypeElement type) {
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

    private List<String> optionValues(String key) {
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

    private void collectProperty(TypeElement owner, Element field) {
        AnnotationMirror property = annotation(field, RUST_PROPERTY);
        if (property == null) {
            return;
        }
        String key = annotationString(property, "value", "");
        String defaultValue = annotationString(property, "defaultValue", "");
        properties.add(key + "\t" + field.asType() + "\t" + defaultValue + "\t"
                + owner.getQualifiedName() + "#" + field.getSimpleName());
    }

    private void collectRoute(TypeElement owner, ExecutableElement method, String basePath) {
        String httpMethod = null;
        String path = null;
        AnnotationMirror rustRoute = annotation(method, RUST_ROUTE);
        AnnotationMirror routeAnnotation = rustRoute;
        if (rustRoute != null) {
            httpMethod = annotationString(rustRoute, "method", "GET").toUpperCase(Locale.ROOT);
            path = annotationString(rustRoute, "path", "/");
        } else {
            for (Map.Entry<String, String> mapping : MAPPINGS.entrySet()) {
                AnnotationMirror route = annotation(method, mapping.getKey());
                if (route != null) {
                    routeAnnotation = route;
                    httpMethod = mapping.getValue();
                    path = annotationString(route, "value", "");
                    break;
                }
            }
        }
        if (httpMethod == null) {
            return;
        }
        String fullPath = combine(basePath, path);
        String key = httpMethod + " " + fullPath;
        String line = key + " " + owner.getQualifiedName() + "#" + method.getSimpleName();
        String previous = routes.putIfAbsent(key, line);
        if (previous != null && !previous.equals(line)) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Duplicate generated route " + key + " owners=[" + previous + ", " + line + "]",
                    method);
        }
        routeOwners.add(owner.getQualifiedName().toString());
        openApiRoutes.putIfAbsent(key, openApiRouteModel(owner, method, httpMethod, fullPath));
        routeMethods.computeIfAbsent(owner.getQualifiedName().toString(), ignored -> new TreeMap<>())
                .putIfAbsent(methodSignature(method), routeMethodModel(
                        method, routeAnnotation, httpMethod, fullPath));
    }

    private OpenApiRouteModel openApiRouteModel(
            TypeElement owner,
            ExecutableElement method,
            String httpMethod,
            String path) {
        AnnotationMirror operation = annotation(method, OPENAPI_OPERATION);
        String defaultOperationId = owner.getSimpleName() + "_" + method.getSimpleName();
        String operationId = operation == null
                ? defaultOperationId
                : annotationString(operation, "operationId", defaultOperationId);
        if (operationId.isBlank()) operationId = defaultOperationId;
        String summary = operation == null ? "" : annotationString(operation, "summary", "");
        String description = operation == null ? "" : annotationString(operation, "description", "");
        List<String> tags = operation == null ? List.of() : annotationStrings(operation, "tags");

        List<OpenApiParameterModel> parameters = new ArrayList<>();
        TypeMirror requestBody = null;
        boolean requestBodyRequired = false;
        for (VariableElement parameter : method.getParameters()) {
            AnnotationMirror body = annotation(parameter, REQUEST_BODY);
            if (body != null) {
                requestBody = parameter.asType();
                requestBodyRequired = annotationBoolean(body, "required", true);
                continue;
            }
            AnnotationMirror pathVariable = annotation(parameter, PATH_VARIABLE);
            AnnotationMirror requestParam = annotation(parameter, REQUEST_PARAM);
            AnnotationMirror header = annotation(parameter, HEADER_PARAM);
            AnnotationMirror cookie = annotation(parameter, COOKIE_VALUE);
            if (pathVariable != null) {
                parameters.add(new OpenApiParameterModel(
                        annotationString(pathVariable, "value", parameter.getSimpleName().toString()),
                        "path", true, "", parameter.asType()));
            } else if (requestParam != null) {
                String defaultValue = annotationString(requestParam, "defaultValue", "");
                parameters.add(new OpenApiParameterModel(
                        annotationString(requestParam, "value", parameter.getSimpleName().toString()),
                        "query",
                        annotationBoolean(requestParam, "required", true) && defaultValue.isEmpty(),
                        defaultValue,
                        parameter.asType()));
            } else if (header != null) {
                String defaultValue = annotationString(header, "defaultValue", "");
                parameters.add(new OpenApiParameterModel(
                        annotationString(header, "value", parameter.getSimpleName().toString()),
                        "header",
                        annotationBoolean(header, "required", false) && defaultValue.isEmpty(),
                        defaultValue,
                        parameter.asType()));
            } else if (cookie != null) {
                String defaultValue = annotationString(cookie, "defaultValue", "");
                parameters.add(new OpenApiParameterModel(
                        annotationString(cookie, "value", parameter.getSimpleName().toString()),
                        "cookie",
                        annotationBoolean(cookie, "required", false) && defaultValue.isEmpty(),
                        defaultValue,
                        parameter.asType()));
            }
        }

        List<OpenApiResponseModel> responses = documentedResponses(method);
        int successStatus = 200;
        AnnotationMirror responseStatus = annotation(method, "com.reactor.rust.annotations.ResponseStatus");
        if (responseStatus != null) successStatus = annotationInt(responseStatus, "value", 200);
        boolean successDocumented = false;
        for (OpenApiResponseModel response : responses) {
            if (response.status() == successStatus) {
                successDocumented = true;
                break;
            }
        }
        if (!successDocumented) {
            responses.add(new OpenApiResponseModel(
                    successStatus,
                    "Success",
                    unwrapResponseType(method.getReturnType())));
        }
        return new OpenApiRouteModel(
                httpMethod.toLowerCase(Locale.ROOT),
                path,
                operationId,
                summary,
                description,
                List.copyOf(tags),
                List.copyOf(parameters),
                requestBody,
                requestBodyRequired,
                List.copyOf(responses));
    }

    private List<OpenApiResponseModel> documentedResponses(ExecutableElement method) {
        List<OpenApiResponseModel> responses = new ArrayList<>();
        AnnotationMirror direct = annotation(method, OPENAPI_RESPONSE);
        if (direct != null) responses.add(openApiResponse(direct));
        AnnotationMirror container = annotation(method, OPENAPI_RESPONSES);
        if (container != null) {
            for (AnnotationMirror nested : annotationMirrors(container, "value")) {
                responses.add(openApiResponse(nested));
            }
        }
        return responses;
    }

    private OpenApiResponseModel openApiResponse(AnnotationMirror response) {
        TypeMirror body = annotationType(response, "body");
        if (body != null && "java.lang.Void".equals(body.toString())) body = null;
        return new OpenApiResponseModel(
                annotationInt(response, "status", 200),
                annotationString(response, "description", "Response"),
                body);
    }

    private TypeMirror unwrapResponseType(TypeMirror type) {
        TypeMirror current = type;
        while (current instanceof DeclaredType declared && !declared.getTypeArguments().isEmpty()) {
            String raw = processingEnv.getTypeUtils().erasure(current).toString();
            if (raw.equals("com.reactor.rust.http.ResponseEntity")
                    || raw.equals("com.reactor.rust.http.HttpResponse")
                    || raw.equals("java.util.Optional")
                    || raw.equals("java.util.concurrent.CompletionStage")
                    || raw.equals("java.util.concurrent.CompletableFuture")) {
                current = declared.getTypeArguments().getFirst();
                continue;
            }
            break;
        }
        if (current.getKind() == TypeKind.VOID
                || "java.lang.Void".equals(current.toString())) return null;
        return current;
    }

    private void generateArtifacts() {
        Map<String, String> generatedValidators = new TreeMap<>();
        for (TypeElement validationType : validationTypes.values()) {
            String generatedType = generateValidator(validationType);
            if (generatedType != null) {
                generatedValidators.put(validationType.getQualifiedName().toString(), generatedType);
            }
        }
        for (ComponentModel component : components.values()) {
            generateComponentFactory(component);
        }
        for (HttpClientModel client : httpClients.values()) {
            generateHttpClient(client);
        }
        writeResource("META-INF/reactor/components.idx", allComponentTypes());
        writeResource("META-INF/reactor/routes.idx", routes.values());
        writeResource("META-INF/reactor/properties.idx", properties);
        writeConfigurationMetadata();
        writeOpenApiDocument();

        String packageName = processingEnv.getOptions().getOrDefault(
                "reactor.codegen.descriptorPackage", "com.reactor.generated");
        boolean validationOnly = components.isEmpty()
                && routes.isEmpty()
                && properties.isEmpty()
                && !generatedValidators.isEmpty();
        String defaultDescriptorName = validationOnly
                ? "ReactorValidationDescriptor_" + stableSuffix(generatedValidators.keySet())
                : "ReactorApplicationDescriptor";
        String simpleName = processingEnv.getOptions().getOrDefault(
                "reactor.codegen.descriptorName", defaultDescriptorName);
        String qualifiedName = packageName + "." + simpleName;
        try {
            JavaFileObject source = processingEnv.getFiler().createSourceFile(qualifiedName);
            try (Writer writer = source.openWriter()) {
                writer.write("package " + packageName + ";\n\n");
                writer.write("public final class " + simpleName
                        + " implements com.reactor.rust.startup.ApplicationDescriptor {\n");
                if (!generatedValidators.isEmpty()) {
                    writer.write("    static {\n");
                    for (String validatorType : generatedValidators.values()) {
                        writer.write("        " + validatorType + ".register();\n");
                    }
                    writer.write("    }\n\n");
                }
                writePackageCoverageMethod(writer, descriptorPackages());
                writer.write("    @Override\n");
                writer.write("    public boolean isComponentEnabled(String componentType) {\n");
                writer.write("        return switch (componentType) {\n");
                for (Map.Entry<String, ConditionsModel> entry : componentConditions.entrySet()) {
                    String expression = conditionExpression(entry.getValue());
                    if (!expression.isEmpty()) {
                        writer.write("            case \"" + escape(entry.getKey()) + "\" -> "
                                + expression + ";\n");
                    }
                }
                writer.write("            default -> true;\n");
                writer.write("        };\n");
                writer.write("    }\n\n");
                writer.write("    @Override\n");
                writer.write("    public int registerComponents(com.reactor.rust.di.BeanContainer container, String basePackage) {\n");
                writer.write("        int registered = 0;\n");
                writer.write("        String prefix = basePackage == null || basePackage.isBlank() ? \"\" : basePackage + \".\";\n");
                for (ComponentModel component : components.values()) {
                    writer.write("        if (prefix.isEmpty() || \"" + escape(component.type())
                            + "\".startsWith(prefix)) {\n");
                    writer.write("            registered += " + component.factoryType() + ".register(container);\n");
                    writer.write("        }\n");
                }
                for (HttpClientModel client : httpClients.values()) {
                    writer.write("        if (prefix.isEmpty() || \"" + escape(client.type())
                            + "\".startsWith(prefix)) {\n");
                    writer.write("            if (!container.hasBean(" + client.type() + ".class)) {\n");
                    writer.write("                container.registerGeneratedFactory(" + client.type()
                            + ".class, () -> new " + client.generatedType()
                            + "(container.getBean(com.reactor.rust.http.client.ReactorHttpClientRuntime.class)), \""
                            + escape(client.beanName()) + "\", false);\n");
                    writer.write("                registered++;\n");
                    writer.write("            }\n");
                    writer.write("        }\n");
                }
                writer.write("        return registered;\n");
                writer.write("    }\n");
                writer.write("\n    @Override\n");
                writer.write("    public int registerConfigurationBeans(com.reactor.rust.di.BeanContainer container, "
                        + "String basePackage) {\n");
                writer.write("        int registered = 0;\n");
                writer.write("        String prefix = basePackage == null || basePackage.isBlank() ? \"\" : basePackage + \".\";\n");
                for (ComponentModel component : components.values()) {
                    if (configurationMethods.getOrDefault(component.type(), List.of()).isEmpty()) {
                        continue;
                    }
                    writer.write("        if (prefix.isEmpty() || \"" + escape(component.type())
                            + "\".startsWith(prefix)) {\n");
                    writer.write("            registered += " + component.factoryType()
                            + ".registerConfigurationBeans(container);\n");
                    writer.write("        }\n");
                }
                writer.write("        return registered;\n");
                writer.write("    }\n");
                writer.write("\n    @Override\n");
                writer.write("    public int registerHandlers(com.reactor.rust.di.BeanContainer container, "
                        + "com.reactor.rust.bridge.HandlerRegistry registry, String basePackage) {\n");
                writer.write("        int registered = 0;\n");
                writer.write("        String prefix = basePackage == null || basePackage.isBlank() ? \"\" : basePackage + \".\";\n");
                for (ComponentModel component : components.values()) {
                    if (!hasHandlerSurface(component)) {
                        continue;
                    }
                    writer.write("        if (prefix.isEmpty() || \"" + escape(component.type())
                            + "\".startsWith(prefix)) {\n");
                    writer.write("            registered += " + component.factoryType()
                            + ".registerHandler(container, registry);\n");
                    writer.write("        }\n");
                }
                writer.write("        return registered;\n");
                writer.write("    }\n");
                writer.write("\n    @Override\n");
                writer.write("    public int registerExceptionHandlers(com.reactor.rust.di.BeanContainer container, "
                        + "com.reactor.rust.exception.ExceptionHandlerRegistry registry, String basePackage) {\n");
                writer.write("        int registered = 0;\n");
                writer.write("        String prefix = basePackage == null || basePackage.isBlank() ? \"\" : basePackage + \".\";\n");
                for (ComponentModel component : components.values()) {
                    if (!hasExceptionHandlerSurface(component)) {
                        continue;
                    }
                    writer.write("        if (prefix.isEmpty() || \"" + escape(component.type())
                            + "\".startsWith(prefix)) {\n");
                    writer.write("            registered += " + component.factoryType()
                            + ".registerExceptionHandlers(container, registry);\n");
                    writer.write("        }\n");
                }
                writer.write("        return registered;\n");
                writer.write("    }\n");
                writer.write("\n    @Override\n");
                writer.write("    public int registerExtensions(com.reactor.rust.di.BeanContainer container, "
                        + "String basePackage) {\n");
                writer.write("        int registered = 0;\n");
                writer.write("        String prefix = basePackage == null || basePackage.isBlank() ? \"\" : basePackage + \".\";\n");
                for (ComponentModel component : components.values()) {
                    if (scheduledMethods.getOrDefault(component.type(), List.of()).isEmpty()) continue;
                    writer.write("        if (prefix.isEmpty() || \"" + escape(component.type())
                            + "\".startsWith(prefix)) {\n");
                    writer.write("            registered += " + component.factoryType()
                            + ".registerScheduledTasks(container);\n");
                    writer.write("        }\n");
                }
                writer.write("        return registered;\n");
                writer.write("    }\n");
                writer.write("}\n");
            }
            writeResource(DESCRIPTOR_SERVICE, List.of(qualifiedName));
        } catch (IOException failure) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Failed to generate application descriptor: " + failure.getMessage());
        }
    }

    private Set<String> allComponentTypes() {
        TreeSet<String> all = new TreeSet<>(components.keySet());
        all.addAll(httpClients.keySet());
        return all;
    }

    private List<String> descriptorPackages() {
        TreeSet<String> packages = new TreeSet<>();
        TreeSet<String> owners = new TreeSet<>(allComponentTypes());
        owners.addAll(routeOwners);
        for (String owner : owners) {
            TypeElement type = processingEnv.getElementUtils().getTypeElement(owner);
            if (type != null) {
                String packageName = processingEnv.getElementUtils().getPackageOf(type)
                        .getQualifiedName().toString();
                if (!packageName.isBlank()) packages.add(packageName);
            }
        }
        return List.copyOf(packages);
    }

    private void writePackageCoverageMethod(Writer writer, List<String> packages) throws IOException {
        writer.write("    @Override\n");
        writer.write("    public boolean coversPackage(String basePackage) {\n");
        if (packages.isEmpty()) {
            writer.write("        return false;\n");
        } else {
            writer.write("        if (basePackage == null || basePackage.isBlank()) return true;\n");
            writer.write("        return ");
            for (int index = 0; index < packages.size(); index++) {
                String packageName = packages.get(index);
                if (index > 0) writer.write("\n                || ");
                writer.write(javaString(packageName) + ".equals(basePackage)"
                        + " || " + javaString(packageName) + ".startsWith(basePackage + \".\")");
            }
            writer.write(";\n");
        }
        writer.write("    }\n\n");
    }

    private void generateHttpClient(HttpClientModel client) {
        try {
            JavaFileObject source = processingEnv.getFiler().createSourceFile(client.generatedType(), client.origin());
            try (Writer writer = source.openWriter()) {
                if (!client.packageName().isEmpty()) {
                    writer.write("package " + client.packageName() + ";\n\n");
                }
                writer.write("public final class " + client.generatedName() + " implements " + client.type() + " {\n");
                writer.write("    private final com.reactor.rust.http.client.ReactorHttpClientRuntime.Client client;\n\n");
                writer.write("    public " + client.generatedName()
                        + "(com.reactor.rust.http.client.ReactorHttpClientRuntime runtime) {\n");
                writer.write("        this.client = runtime.client(" + javaString(client.baseUrlProperty()) + ");\n");
                writer.write("    }\n\n");
                for (HttpClientMethodModel method : client.methods()) {
                    writer.write("    @Override\n");
                    writer.write("    public " + method.returnType() + " " + method.name() + "(");
                    for (int index = 0; index < method.parameters().size(); index++) {
                        if (index > 0) writer.write(", ");
                        HttpClientParameterModel parameter = method.parameters().get(index);
                        writer.write(parameter.type() + " arg" + parameter.index());
                    }
                    writer.write(") {\n");
                    writer.write("        com.reactor.rust.http.client.ReactorHttpClientRuntime.Request request = client.request("
                            + javaString(method.httpMethod()) + ", " + javaString(method.path()) + ", "
                            + javaString(method.contentType()) + ", " + javaString(method.accept()) + ", "
                            + method.timeoutMs() + "L, "
                            + method.retries() + ", " + method.idempotent() + ");\n");
                    for (HttpClientParameterModel parameter : method.parameters()) {
                        String argument = "arg" + parameter.index()
                                + (parameter.optional() ? ".orElse(null)" : "");
                        switch (parameter.kind()) {
                            case "PATH" -> writer.write("        request.path(" + javaString(parameter.name())
                                    + ", " + argument + ");\n");
                            case "QUERY" -> writer.write("        request.query(" + javaString(parameter.name())
                                    + ", " + argument + ");\n");
                            case "HEADER" -> writer.write("        request.header(" + javaString(parameter.name())
                                    + ", " + argument + ");\n");
                            case "BODY" -> writer.write("        request.body(" + argument + ");\n");
                            default -> throw new IllegalStateException(
                                    "Unknown generated HTTP parameter kind " + parameter.kind());
                        }
                    }
                    String operation = method.responseKind().equals("LIST")
                            ? (method.responseWrapper() ? "executeListResponse" : "executeList")
                            : (method.responseWrapper() ? "executeResponse" : "execute");
                    writer.write("        return request."
                            + operation
                            + "(" + method.responseType() + ".class);\n");
                    writer.write("    }\n\n");
                }
                writer.write("}\n");
            }
        } catch (IOException failure) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Failed to generate HTTP client " + client.type() + ": " + failure.getMessage(),
                    client.origin());
        }
    }

    private static String stableSuffix(Iterable<String> values) {
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

    private List<String> conditionMetadata() {
        List<String> metadata = new ArrayList<>();
        for (Map.Entry<String, ConditionsModel> entry : componentConditions.entrySet()) {
            ConditionsModel conditions = entry.getValue();
            if (!conditions.properties().isEmpty() || !conditions.profiles().isEmpty()) {
                metadata.add(entry.getKey());
            }
        }
        return metadata;
    }

    private List<String> healthRouteMetadata() {
        List<String> healthRoutes = new ArrayList<>();
        for (String route : routes.values()) {
            String normalized = route.toLowerCase(Locale.ROOT);
            if (normalized.contains(" /app/health ")
                    || normalized.contains(" /app/readiness ")
                    || normalized.contains(" /app/liveness ")) {
                healthRoutes.add(route);
            }
        }
        return healthRoutes;
    }

    private String generateValidator(TypeElement type) {
        String packageName = processingEnv.getElementUtils().getPackageOf(type)
                .getQualifiedName().toString();
        String localTypeName = type.getQualifiedName().toString();
        if (!packageName.isEmpty()) {
            localTypeName = localTypeName.substring(packageName.length() + 1);
        }
        String simpleName = localTypeName.replace('.', '_') + "__ReactorValidator";
        String qualifiedName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        Map<String, String> patterns = validatorPatterns(type);
        try {
            JavaFileObject source = processingEnv.getFiler().createSourceFile(qualifiedName, type);
            try (Writer writer = source.openWriter()) {
                if (!packageName.isEmpty()) {
                    writer.write("package " + packageName + ";\n\n");
                }
                writer.write("public final class " + simpleName + " {\n");
                writer.write("    private " + simpleName + "() {}\n\n");
                int patternIndex = 0;
                for (String expression : patterns.values()) {
                    writer.write("    private static final java.util.regex.Pattern PATTERN_"
                            + patternIndex++ + " = java.util.regex.Pattern.compile(\""
                            + escape(expression) + "\");\n");
                }
                if (!patterns.isEmpty()) writer.write("\n");
                writer.write("    public static void register() {\n");
                writer.write("        com.reactor.rust.validation.GeneratedValidators.register("
                        + type.getQualifiedName() + ".class, " + simpleName + "::validate, ");
                writeDefaultValues(writer, type);
                writer.write(");\n");
                writer.write("    }\n\n");
                writer.write("    private static com.reactor.rust.validation.ValidationResult validate(Object raw) {\n");
                writer.write("        " + type.getQualifiedName() + " value = ("
                        + type.getQualifiedName() + ") raw;\n");
                writer.write("        java.util.ArrayList<com.reactor.rust.validation.ConstraintViolation> violations = null;\n");
                int fieldIndex = 0;
                for (RecordComponentElement component : type.getRecordComponents()) {
                    writeFieldValidation(writer, type, component, fieldIndex++, patterns);
                }
                writer.write("        return com.reactor.rust.validation.GeneratedValidationSupport.result(violations);\n");
                writer.write("    }\n");
                writer.write("}\n");
            }
            return qualifiedName;
        } catch (IOException failure) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Failed to generate validator for " + type.getQualifiedName() + ": " + failure.getMessage(),
                    type);
            return null;
        }
    }

    private Map<String, String> validatorPatterns(TypeElement type) {
        Map<String, String> patterns = new LinkedHashMap<>();
        for (RecordComponentElement component : type.getRecordComponents()) {
            AnnotationMirror pattern = validationAnnotation(type, component, PATTERN);
            if (pattern != null) {
                patterns.put(component.getSimpleName() + "#pattern",
                        annotationString(pattern, "regexp", ""));
            }
            AnnotationMirror field = validationAnnotation(type, component, FIELD);
            String fieldPattern = field == null ? "" : annotationString(field, "pattern", "");
            if (!fieldPattern.isEmpty()) {
                patterns.put(component.getSimpleName() + "#field", fieldPattern);
            }
        }
        return patterns;
    }

    private void writeDefaultValues(Writer writer, TypeElement type) throws IOException {
        List<String> entries = new ArrayList<>();
        for (RecordComponentElement component : type.getRecordComponents()) {
            AnnotationMirror field = validationAnnotation(type, component, FIELD);
            String defaultValue = field == null ? "" : annotationString(field, "defaultValue", "");
            if (!defaultValue.isEmpty()) {
                entries.add("java.util.Map.entry(\"" + escape(component.getSimpleName().toString())
                        + "\", \"" + escape(defaultValue) + "\")");
            }
        }
        if (entries.isEmpty()) {
            writer.write("java.util.Map.of()");
        } else {
            writer.write("java.util.Map.ofEntries(" + String.join(", ", entries) + ")");
        }
    }

    private void writeFieldValidation(
            Writer writer,
            TypeElement owner,
            RecordComponentElement component,
            int fieldIndex,
            Map<String, String> patterns) throws IOException {
        String fieldName = component.getSimpleName().toString();
        String variable = "field" + fieldIndex;
        writer.write("        var " + variable + " = value." + fieldName + "();\n");
        AnnotationMirror notNull = validationAnnotation(owner, component, NOT_NULL);
        AnnotationMirror field = validationAnnotation(owner, component, FIELD);
        boolean required = field != null && annotationBoolean(field, "required", false);
        boolean primitive = component.asType().getKind().isPrimitive();
        if (!primitive) {
            writer.write("        if (" + variable + " == null) {\n");
            if (notNull != null) {
                writeViolation(writer, "            ", fieldName,
                        annotationString(notNull, "message", "must not be null"), "null");
            } else if (required) {
                writeViolation(writer, "            ", fieldName, "is required", "null");
            }
            writer.write("        } else {\n");
            writeNonNullValidations(writer, owner, component, variable, "            ", patterns, field);
            writer.write("        }\n");
        } else {
            writeNonNullValidations(writer, owner, component, variable, "        ", patterns, field);
        }
    }

    private void writeNonNullValidations(
            Writer writer,
            TypeElement owner,
            RecordComponentElement component,
            String variable,
            String indent,
            Map<String, String> patterns,
            AnnotationMirror field) throws IOException {
        String name = component.getSimpleName().toString();
        AnnotationMirror notBlank = validationAnnotation(owner, component, NOT_BLANK);
        if (notBlank != null) {
            writeConditionalViolation(writer, indent,
                    "com.reactor.rust.validation.GeneratedValidationSupport.isBlank(" + variable + ")",
                    name, annotationString(notBlank, "message", "must not be blank"), variable);
        }
        AnnotationMirror notEmpty = validationAnnotation(owner, component, NOT_EMPTY);
        if (notEmpty != null) {
            writeConditionalViolation(writer, indent,
                    "com.reactor.rust.validation.GeneratedValidationSupport.isEmpty(" + variable + ")",
                    name, annotationString(notEmpty, "message", "must not be empty"), variable);
        }
        AnnotationMirror size = validationAnnotation(owner, component, SIZE);
        if (size != null) {
            int min = annotationInt(size, "min", 0);
            int max = annotationInt(size, "max", Integer.MAX_VALUE);
            String length = "com.reactor.rust.validation.GeneratedValidationSupport.length(" + variable + ")";
            String message = annotationString(size, "message", "size must be between {min} and {max}")
                    .replace("{min}", Integer.toString(min))
                    .replace("{max}", Integer.toString(max));
            writeConditionalViolation(writer, indent,
                    length + " < " + min + " || " + length + " > " + max,
                    name, message, variable);
        }
        AnnotationMirror email = validationAnnotation(owner, component, EMAIL);
        if (email != null) {
            writeConditionalViolation(writer, indent,
                    "com.reactor.rust.validation.GeneratedValidationSupport.invalidEmailIfString(" + variable + ")",
                    name, annotationString(email, "message", "must be a valid email address"), variable);
        }
        AnnotationMirror pattern = validationAnnotation(owner, component, PATTERN);
        if (pattern != null) {
            writeConditionalViolation(writer, indent,
                    "com.reactor.rust.validation.GeneratedValidationSupport.mismatchesIfString("
                            + patternName(patterns, name + "#pattern") + ", " + variable + ")",
                    name,
                    annotationString(pattern, "message", "must match pattern '{regexp}'")
                            .replace("{regexp}", annotationString(pattern, "regexp", "")),
                    variable);
        }
        writeNumericConstraint(writer, component, validationAnnotation(owner, component, MIN),
                "value", "<", indent, name, variable, true);
        writeNumericConstraint(writer, component, validationAnnotation(owner, component, MAX),
                "value", ">", indent, name, variable, true);
        AnnotationMirror positive = validationAnnotation(owner, component, POSITIVE);
        if (positive != null) {
            writeNumericCondition(writer, component, indent, name, variable, "<=", "0",
                    annotationString(positive, "message", "must be positive"));
        }
        AnnotationMirror negative = validationAnnotation(owner, component, NEGATIVE);
        if (negative != null) {
            writeNumericCondition(writer, component, indent, name, variable, ">=", "0",
                    annotationString(negative, "message", "must be negative"));
        }
        writeNumericConstraint(writer, component, validationAnnotation(owner, component, DECIMAL_MIN),
                "value", "<", indent, name, variable, false);
        writeNumericConstraint(writer, component, validationAnnotation(owner, component, DECIMAL_MAX),
                "value", ">", indent, name, variable, false);
        if (field != null) {
            if (annotationBoolean(field, "required", false)) {
                writeConditionalViolation(writer, indent,
                        "com.reactor.rust.validation.GeneratedValidationSupport.isBlankString(" + variable + ")",
                        name, "is required and cannot be blank", variable);
            }
            String fieldPattern = annotationString(field, "pattern", "");
            if (!fieldPattern.isEmpty()) {
                writeConditionalViolation(writer, indent,
                        "com.reactor.rust.validation.GeneratedValidationSupport.mismatchesIfString("
                                + patternName(patterns, name + "#field") + ", " + variable + ")",
                        name, "does not match pattern: " + fieldPattern, variable);
            }
            double min = annotationDouble(field, "min", Double.MIN_VALUE);
            if (Double.compare(min, Double.MIN_VALUE) != 0) {
                writeNumericCondition(writer, component, indent, name, variable, "<",
                        Double.toString(min), "must be >= " + min);
            }
            double max = annotationDouble(field, "max", Double.MAX_VALUE);
            if (Double.compare(max, Double.MAX_VALUE) != 0) {
                writeNumericCondition(writer, component, indent, name, variable, ">",
                        Double.toString(max), "must be <= " + max);
            }
        }
    }

    private void writeNumericConstraint(
            Writer writer,
            RecordComponentElement component,
            AnnotationMirror constraint,
            String key,
            String operator,
            String indent,
            String fieldName,
            String variable,
            boolean integral) throws IOException {
        if (constraint == null) return;
        String threshold = integral
                ? Long.toString(annotationLong(constraint, key, 0))
                : annotationString(constraint, key, "0");
        String message = annotationString(constraint, "message", "invalid numeric value")
                .replace("{value}", threshold);
        writeNumericCondition(writer, component, indent, fieldName, variable,
                operator, integral ? threshold + "L" : threshold, message);
    }

    private void writeNumericCondition(
            Writer writer,
            RecordComponentElement component,
            String indent,
            String fieldName,
            String variable,
            String operator,
            String threshold,
            String message) throws IOException {
        boolean primitiveNumber = switch (component.asType().getKind()) {
            case BYTE, SHORT, INT, LONG, FLOAT, DOUBLE -> true;
            default -> false;
        };
        String number = primitiveNumber
                ? variable
                : "com.reactor.rust.validation.GeneratedValidationSupport.number(" + variable + ")";
        String condition = number + " " + operator + " " + threshold;
        if (!primitiveNumber) {
            condition = "com.reactor.rust.validation.GeneratedValidationSupport.isNumber("
                    + variable + ") && " + condition;
        }
        writeConditionalViolation(writer, indent, condition, fieldName, message, variable);
    }

    private void writeConditionalViolation(
            Writer writer,
            String indent,
            String condition,
            String fieldName,
            String message,
            String invalidValue) throws IOException {
        writer.write(indent + "if (" + condition + ") {\n");
        writeViolation(writer, indent + "    ", fieldName, message, invalidValue);
        writer.write(indent + "}\n");
    }

    private void writeViolation(
            Writer writer,
            String indent,
            String fieldName,
            String message,
            String invalidValue) throws IOException {
        writer.write(indent + "violations = com.reactor.rust.validation.GeneratedValidationSupport.add("
                + "violations, \"" + escape(fieldName) + "\", \"" + escape(message)
                + "\", " + invalidValue + ");\n");
    }

    private AnnotationMirror validationAnnotation(
            TypeElement owner,
            RecordComponentElement component,
            String annotationName) {
        AnnotationMirror direct = annotation(component, annotationName);
        if (direct != null) return direct;
        for (Element enclosed : owner.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.FIELD
                    && enclosed.getSimpleName().contentEquals(component.getSimpleName())) {
                return annotation(enclosed, annotationName);
            }
        }
        return null;
    }

    private int annotationInt(AnnotationMirror mirror, String key, int fallback) {
        Object value = annotationValue(mirror, key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private long annotationLong(AnnotationMirror mirror, String key, long fallback) {
        Object value = annotationValue(mirror, key);
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private double annotationDouble(AnnotationMirror mirror, String key, double fallback) {
        Object value = annotationValue(mirror, key);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private static String patternName(Map<String, String> patterns, String key) {
        int index = 0;
        for (String candidate : patterns.keySet()) {
            if (candidate.equals(key)) return "PATTERN_" + index;
            index++;
        }
        throw new IllegalStateException("Missing generated validation pattern " + key);
    }

    private void writeOpenApiDocument() {
        if (openApiRoutes.isEmpty()) return;
        Map<String, List<OpenApiRouteModel>> paths = new TreeMap<>();
        for (OpenApiRouteModel route : openApiRoutes.values()) {
            paths.computeIfAbsent(route.path(), ignored -> new ArrayList<>()).add(route);
        }
        try {
            FileObject resource = processingEnv.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT, "", "META-INF/reactor/openapi.json");
            try (Writer writer = resource.openWriter()) {
                writer.write("{\"openapi\":\"3.1.0\",\"info\":{\"title\":\""
                        + jsonEscape(openApiTitle) + "\",\"version\":\""
                        + jsonEscape(openApiVersion) + "\"");
                if (!openApiDescription.isEmpty()) {
                    writer.write(",\"description\":\"" + jsonEscape(openApiDescription) + "\"");
                }
                writer.write("},\"paths\":{");
                int pathIndex = 0;
                for (Map.Entry<String, List<OpenApiRouteModel>> path : paths.entrySet()) {
                    if (pathIndex++ > 0) writer.write(",");
                    writer.write("\"" + jsonEscape(path.getKey()) + "\":{");
                    int operationIndex = 0;
                    for (OpenApiRouteModel operation : path.getValue()) {
                        if (operationIndex++ > 0) writer.write(",");
                        writeOpenApiOperation(writer, operation);
                    }
                    writer.write("}");
                }
                writer.write("},\"components\":{\"schemas\":{");
                writer.write("\"ProblemDetail\":{\"type\":\"object\",\"required\":[\"title\",\"status\"],"
                        + "\"properties\":{\"type\":{\"type\":\"string\"},"
                        + "\"title\":{\"type\":\"string\"},\"status\":{\"type\":\"integer\",\"format\":\"int32\"},"
                        + "\"detail\":{\"type\":\"string\"},\"code\":{\"type\":\"string\"}}}");
                for (Map.Entry<String, TypeElement> schema : validationTypes.entrySet()) {
                    writer.write(",\"" + jsonEscape(schemaName(schema.getKey())) + "\":");
                    writeRecordSchema(writer, schema.getValue());
                }
                writer.write("}}}");
            }
        } catch (IOException failure) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Failed to generate OpenAPI document: " + failure.getMessage());
        }
    }

    private void writeOpenApiOperation(Writer writer, OpenApiRouteModel operation) throws IOException {
        writer.write("\"" + operation.method() + "\":{\"operationId\":\""
                + jsonEscape(operation.operationId()) + "\"");
        if (!operation.summary().isBlank()) {
            writer.write(",\"summary\":\"" + jsonEscape(operation.summary()) + "\"");
        }
        if (!operation.description().isBlank()) {
            writer.write(",\"description\":\"" + jsonEscape(operation.description()) + "\"");
        }
        if (!operation.tags().isEmpty()) {
            writer.write(",\"tags\":[");
            for (int index = 0; index < operation.tags().size(); index++) {
                if (index > 0) writer.write(",");
                writer.write("\"" + jsonEscape(operation.tags().get(index)) + "\"");
            }
            writer.write("]");
        }
        if (!operation.parameters().isEmpty()) {
            writer.write(",\"parameters\":[");
            for (int index = 0; index < operation.parameters().size(); index++) {
                if (index > 0) writer.write(",");
                OpenApiParameterModel parameter = operation.parameters().get(index);
                writer.write("{\"name\":\"" + jsonEscape(parameter.name()) + "\",\"in\":\""
                        + parameter.location() + "\",\"required\":" + parameter.required()
                        + ",\"schema\":" + schemaJson(parameter.type(), parameter.defaultValue()) + "}");
            }
            writer.write("]");
        }
        if (operation.requestBody() != null) {
            writer.write(",\"requestBody\":{\"required\":" + operation.requestBodyRequired()
                    + ",\"content\":{\"application/json\":{\"schema\":"
                    + schemaJson(operation.requestBody(), "") + "}}}");
        }
        writer.write(",\"responses\":{");
        Map<Integer, OpenApiResponseModel> responses = new TreeMap<>();
        for (OpenApiResponseModel response : operation.responses()) {
            responses.putIfAbsent(response.status(), response);
        }
        responses.putIfAbsent(400, new OpenApiResponseModel(400, "Invalid request", null));
        responses.putIfAbsent(500, new OpenApiResponseModel(500, "Internal server error", null));
        int index = 0;
        for (OpenApiResponseModel response : responses.values()) {
            if (index++ > 0) writer.write(",");
            writer.write("\"" + response.status() + "\":{\"description\":\""
                    + jsonEscape(response.description()) + "\"");
            if (response.body() != null) {
                writer.write(",\"content\":{\"application/json\":{\"schema\":"
                        + schemaJson(response.body(), "") + "}}");
            } else if (response.status() >= 400) {
                writer.write(",\"content\":{\"application/problem+json\":{\"schema\":{"
                        + "\"$ref\":\"#/components/schemas/ProblemDetail\"}}}");
            }
            writer.write("}");
        }
        writer.write("}}");
    }

    private void writeRecordSchema(Writer writer, TypeElement type) throws IOException {
        writer.write("{\"type\":\"object\",\"properties\":{");
        List<? extends RecordComponentElement> components = type.getRecordComponents();
        List<String> required = new ArrayList<>();
        for (int index = 0; index < components.size(); index++) {
            if (index > 0) writer.write(",");
            RecordComponentElement component = components.get(index);
            writer.write("\"" + jsonEscape(component.getSimpleName().toString()) + "\":"
                    + schemaJson(component.asType(), ""));
            if (component.asType().getKind().isPrimitive()
                    || validationAnnotation(type, component, NOT_NULL) != null
                    || validationAnnotation(type, component, NOT_BLANK) != null
                    || validationAnnotation(type, component, NOT_EMPTY) != null) {
                required.add(component.getSimpleName().toString());
            }
        }
        writer.write("}");
        if (!required.isEmpty()) {
            writer.write(",\"required\":[");
            for (int index = 0; index < required.size(); index++) {
                if (index > 0) writer.write(",");
                writer.write("\"" + jsonEscape(required.get(index)) + "\"");
            }
            writer.write("]");
        }
        writer.write("}");
    }

    private String schemaJson(TypeMirror type, String defaultValue) {
        if (type == null) return "{}";
        String schema;
        if (type instanceof ArrayType array) {
            if (array.getComponentType().getKind() == TypeKind.BYTE) {
                schema = "{\"type\":\"string\",\"format\":\"byte\"}";
            } else {
                schema = "{\"type\":\"array\",\"items\":" + schemaJson(array.getComponentType(), "") + "}";
            }
        } else if (type instanceof DeclaredType declared) {
            String raw = processingEnv.getTypeUtils().erasure(type).toString();
            if ((raw.equals("java.util.List") || raw.equals("java.util.Collection")
                    || raw.equals("java.util.Set")) && !declared.getTypeArguments().isEmpty()) {
                schema = "{\"type\":\"array\",\"items\":"
                        + schemaJson(declared.getTypeArguments().getFirst(), "") + "}";
            } else if (raw.equals("java.util.Map")) {
                schema = "{\"type\":\"object\",\"additionalProperties\":true}";
            } else if (raw.equals("java.lang.String") || raw.equals("java.lang.Character")
                    || raw.startsWith("java.time.") || raw.equals("java.util.UUID")) {
                schema = "{\"type\":\"string\"}";
            } else if (raw.equals("java.lang.Boolean")) {
                schema = "{\"type\":\"boolean\"}";
            } else if (raw.equals("java.lang.Integer") || raw.equals("java.lang.Short")
                    || raw.equals("java.lang.Byte")) {
                schema = "{\"type\":\"integer\",\"format\":\"int32\"}";
            } else if (raw.equals("java.lang.Long")) {
                schema = "{\"type\":\"integer\",\"format\":\"int64\"}";
            } else if (raw.equals("java.lang.Double") || raw.equals("java.lang.Float")
                    || raw.equals("java.math.BigDecimal")) {
                schema = "{\"type\":\"number\",\"format\":\"double\"}";
            } else if (validationTypes.containsKey(raw)) {
                schema = "{\"$ref\":\"#/components/schemas/" + jsonEscape(schemaName(raw)) + "\"}";
            } else {
                TypeElement element = (TypeElement) declared.asElement();
                schema = element.getKind() == ElementKind.ENUM
                        ? enumSchema(element)
                        : "{\"type\":\"object\",\"additionalProperties\":true}";
            }
        } else {
            schema = switch (type.getKind()) {
                case BOOLEAN -> "{\"type\":\"boolean\"}";
                case BYTE, SHORT, INT -> "{\"type\":\"integer\",\"format\":\"int32\"}";
                case LONG -> "{\"type\":\"integer\",\"format\":\"int64\"}";
                case FLOAT, DOUBLE -> "{\"type\":\"number\",\"format\":\"double\"}";
                default -> "{}";
            };
        }
        if (defaultValue == null || defaultValue.isEmpty() || !schema.endsWith("}")) return schema;
        String value = (type.getKind().isPrimitive() && type.getKind() != TypeKind.CHAR)
                ? defaultValue.toLowerCase(Locale.ROOT)
                : "\"" + jsonEscape(defaultValue) + "\"";
        return schema.substring(0, schema.length() - 1) + ",\"default\":" + value + "}";
    }

    private static String enumSchema(TypeElement type) {
        StringBuilder schema = new StringBuilder("{\"type\":\"string\",\"enum\":[");
        boolean first = true;
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.ENUM_CONSTANT) continue;
            if (!first) schema.append(',');
            first = false;
            schema.append('\"').append(jsonEscape(enclosed.getSimpleName().toString())).append('\"');
        }
        return schema.append("]}").toString();
    }

    private static String schemaName(String type) {
        return type.replace('$', '.');
    }

    private void writeListMethod(Writer writer, String name, List<String> values) throws IOException {
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

    private void writeResource(String name, Iterable<String> lines) {
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

    private void writeConfigurationMetadata() {
        StringBuilder json = new StringBuilder(256).append("{\"groups\":[");
        boolean firstGroup = true;
        for (Map.Entry<String, ConfigurationRecordModel> entry : configurationRecords.entrySet()) {
            if (!firstGroup) json.append(',');
            firstGroup = false;
            json.append("{\"name\":\"").append(jsonEscape(entry.getValue().prefix()))
                    .append("\",\"type\":\"").append(jsonEscape(entry.getKey())).append("\"}");
        }
        json.append("],\"properties\":[");
        boolean firstProperty = true;
        for (Map.Entry<String, ConfigurationRecordModel> entry : configurationRecords.entrySet()) {
            for (ConfigurationPropertyModel property : entry.getValue().properties()) {
                if (!firstProperty) json.append(',');
                firstProperty = false;
                json.append("{\"name\":\"").append(jsonEscape(property.key()))
                        .append("\",\"type\":\"").append(jsonEscape(property.type()))
                        .append("\",\"sourceType\":\"").append(jsonEscape(entry.getKey())).append('"');
                if (property.defaultValue() != null) {
                    json.append(",\"defaultValue\":\"")
                            .append(jsonEscape(property.defaultValue())).append('"');
                }
                json.append('}');
            }
        }
        json.append("]}");
        writeResource("META-INF/reactor/configuration-metadata.json", List.of(json.toString()));
    }

    private ComponentAnnotation componentAnnotation(TypeElement type) {
        for (AnnotationMirror mirror : type.getAnnotationMirrors()) {
            TypeElement annotationType = (TypeElement) mirror.getAnnotationType().asElement();
            String annotationName = annotationType.getQualifiedName().toString();
            if (annotationName.equals(COMPONENT)) {
                return new ComponentAnnotation(annotationString(mirror, "value", ""));
            }
            if (hasAnnotation(annotationType, COMPONENT)) {
                String beanName = annotationName.equals(SERVICE) || annotationName.equals(REPOSITORY)
                        ? annotationString(mirror, "value", "")
                        : "";
                return new ComponentAnnotation(beanName);
            }
        }
        return null;
    }

    private String controllerBasePath(TypeElement type) {
        AnnotationMirror requestMapping = annotation(type, REQUEST_MAPPING);
        if (requestMapping != null) {
            return annotationString(requestMapping, "value", "");
        }
        AnnotationMirror controller = annotation(type, REST_CONTROLLER);
        return controller == null ? "" : annotationString(controller, "value", "");
    }

    private ComponentModel componentModel(TypeElement type, ComponentAnnotation component) {
        if (type.getModifiers().contains(Modifier.ABSTRACT)) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Generated component cannot be abstract: " + type.getQualifiedName(),
                    type);
        }
        if (!type.getTypeParameters().isEmpty()) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Generated component cannot declare type parameters: " + type.getQualifiedName(),
                    type);
        }
        List<ExecutableElement> constructors = type.getEnclosedElements().stream()
                .filter(element -> element.getKind() == ElementKind.CONSTRUCTOR)
                .map(ExecutableElement.class::cast)
                .toList();
        ExecutableElement selected = selectConstructor(type, constructors);
        List<DependencyModel> dependencies = selected == null
                ? List.of()
                : selected.getParameters().stream().map(this::dependencyModel).toList();
        return componentModel(type, component, dependencies);
    }

    private ComponentModel componentModel(
            TypeElement type,
            ComponentAnnotation component,
            List<DependencyModel> dependencies) {
        String packageName = processingEnv.getElementUtils().getPackageOf(type)
                .getQualifiedName().toString();
        String factoryName = type.getSimpleName() + "__ReactorFactory";
        String factoryType = packageName.isEmpty() ? factoryName : packageName + "." + factoryName;
        return new ComponentModel(
                type.getQualifiedName().toString(),
                packageName,
                factoryName,
                factoryType,
                component.name().isBlank() ? beanName(type.getSimpleName().toString()) : component.name(),
                hasAnnotation(type, PRIMARY),
                dependencies,
                exposedTypes(type));
    }

    private ConfigurationRecordModel configurationRecordModel(
            TypeElement type,
            AnnotationMirror annotation) {
        if (type.getKind() != ElementKind.RECORD) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@ConfigurationProperties requires an immutable record",
                    type);
        }
        String prefix = annotationString(annotation, "value", "").trim();
        while (prefix.endsWith(".")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        List<ConfigurationPropertyModel> values = new ArrayList<>();
        for (RecordComponentElement component : type.getRecordComponents()) {
            String localName = annotationString(component, CONFIG_NAME, "value", "");
            if (localName.isBlank()) {
                localName = kebabCase(component.getSimpleName().toString());
            }
            String key = prefix.isBlank() ? localName : prefix + "." + localName;
            AnnotationMirror defaultAnnotation = annotation(component, CONFIG_DEFAULT);
            String defaultValue = defaultAnnotation == null
                    ? null
                    : annotationString(defaultAnnotation, "value", "");
            String expression = configurationExpression(component, key, defaultValue);
            values.add(new ConfigurationPropertyModel(
                    key,
                    component.asType().toString(),
                    defaultValue,
                    expression));
            properties.add(key + "\t" + component.asType() + "\t"
                    + (defaultValue == null ? "" : defaultValue) + "\t"
                    + type.getQualifiedName() + "#" + component.getSimpleName());
        }
        return new ConfigurationRecordModel(prefix, List.copyOf(values));
    }

    private String configurationExpression(
            RecordComponentElement component,
            String key,
            String defaultValue) {
        TypeMirror type = component.asType();
        String erased = processingEnv.getTypeUtils().erasure(type).toString();
        String keyLiteral = javaString(key);
        String defaultLiteral = javaString(defaultValue);
        return switch (type.getKind()) {
            case INT -> "com.reactor.rust.config.ConfigurationBinder.integer(" + keyLiteral + ", "
                    + defaultLiteral + ")";
            case LONG -> "com.reactor.rust.config.ConfigurationBinder.longValue(" + keyLiteral + ", "
                    + defaultLiteral + ")";
            case SHORT -> "com.reactor.rust.config.ConfigurationBinder.shortValue(" + keyLiteral + ", "
                    + defaultLiteral + ")";
            case DOUBLE -> "com.reactor.rust.config.ConfigurationBinder.doubleValue(" + keyLiteral + ", "
                    + defaultLiteral + ")";
            case BOOLEAN -> "com.reactor.rust.config.ConfigurationBinder.booleanValue(" + keyLiteral + ", "
                    + defaultLiteral + ")";
            case DECLARED -> declaredConfigurationExpression(component, erased, keyLiteral, defaultLiteral,
                    defaultValue);
            default -> unsupportedConfigurationType(component);
        };
    }

    private String declaredConfigurationExpression(
            RecordComponentElement component,
            String erased,
            String keyLiteral,
            String defaultLiteral,
            String defaultValue) {
        if (erased.equals(String.class.getName())) {
            return "com.reactor.rust.config.ConfigurationBinder.string(" + keyLiteral + ", "
                    + defaultLiteral + ", " + (defaultValue == null) + ")";
        }
        if (erased.equals(Integer.class.getName())) {
            return "java.lang.Integer.valueOf(com.reactor.rust.config.ConfigurationBinder.integer("
                    + keyLiteral + ", " + defaultLiteral + "))";
        }
        if (erased.equals(Long.class.getName())) {
            return "java.lang.Long.valueOf(com.reactor.rust.config.ConfigurationBinder.longValue("
                    + keyLiteral + ", " + defaultLiteral + "))";
        }
        if (erased.equals(Short.class.getName())) {
            return "java.lang.Short.valueOf(com.reactor.rust.config.ConfigurationBinder.shortValue("
                    + keyLiteral + ", " + defaultLiteral + "))";
        }
        if (erased.equals(Double.class.getName())) {
            return "java.lang.Double.valueOf(com.reactor.rust.config.ConfigurationBinder.doubleValue("
                    + keyLiteral + ", " + defaultLiteral + "))";
        }
        if (erased.equals(Boolean.class.getName())) {
            return "java.lang.Boolean.valueOf(com.reactor.rust.config.ConfigurationBinder.booleanValue("
                    + keyLiteral + ", " + defaultLiteral + "))";
        }
        if (erased.equals("java.time.Duration")) {
            return "com.reactor.rust.config.ConfigurationBinder.duration(" + keyLiteral + ", "
                    + defaultLiteral + ")";
        }
        if (erased.equals("java.util.Optional")
                && component.asType().toString().equals("java.util.Optional<java.lang.String>")) {
            return "com.reactor.rust.config.ConfigurationBinder.optionalString(" + keyLiteral + ")";
        }
        if (erased.equals("java.util.List")
                && component.asType().toString().equals("java.util.List<java.lang.String>")) {
            return "com.reactor.rust.config.ConfigurationBinder.stringList(" + keyLiteral + ", "
                    + defaultLiteral + ")";
        }
        Element declared = processingEnv.getTypeUtils().asElement(component.asType());
        if (declared != null && declared.getKind() == ElementKind.ENUM) {
            return "com.reactor.rust.config.ConfigurationBinder.enumValue(" + keyLiteral + ", "
                    + defaultLiteral + ", " + erased + ".class)";
        }
        return unsupportedConfigurationType(component);
    }

    private String unsupportedConfigurationType(RecordComponentElement component) {
        processingEnv.getMessager().printMessage(
                Diagnostic.Kind.ERROR,
                "Unsupported @ConfigurationProperties component type: " + component.asType()
                        + "; use String, primitive/boxed scalar, Duration, enum, Optional<String> or List<String>",
                component);
        return "null";
    }

    private ConditionsModel conditions(Element type) {
        List<PropertyConditionModel> properties = new ArrayList<>();
        List<String> profiles = new ArrayList<>();
        for (AnnotationMirror mirror : type.getAnnotationMirrors()) {
            String name = mirror.getAnnotationType().toString();
            if (name.equals(REQUIRES_PROPERTY)) {
                properties.add(propertyCondition(mirror));
            } else if (name.equals(REQUIRES_PROPERTIES)) {
                for (AnnotationMirror nested : annotationMirrors(mirror, "value")) {
                    properties.add(propertyCondition(nested));
                }
            } else if (name.equals(PROFILE)) {
                profiles.addAll(annotationStrings(mirror, "value"));
            }
        }
        return new ConditionsModel(List.copyOf(properties), List.copyOf(profiles));
    }

    private PropertyConditionModel propertyCondition(AnnotationMirror mirror) {
        return new PropertyConditionModel(
                annotationString(mirror, "name", ""),
                annotationString(mirror, "value", ""),
                annotationBoolean(mirror, "matchIfMissing", false));
    }

    private ExecutableElement selectConstructor(TypeElement type, List<ExecutableElement> constructors) {
        if (constructors.isEmpty()) {
            return null;
        }
        List<ExecutableElement> autowired = constructors.stream()
                .filter(constructor -> hasAnnotation(
                        constructor,
                        "com.reactor.rust.di.annotation.Autowired"))
                .toList();
        ExecutableElement selected;
        if (autowired.size() == 1) {
            selected = autowired.get(0);
        } else if (autowired.size() > 1) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Only one constructor may declare @Autowired",
                    type);
            return constructors.get(0);
        } else if (constructors.size() == 1) {
            selected = constructors.get(0);
        } else {
            selected = constructors.stream()
                    .filter(constructor -> constructor.getParameters().isEmpty())
                    .findFirst()
                    .orElse(null);
            if (selected == null) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "Multiple constructors require one @Autowired constructor: " + type.getQualifiedName(),
                        type);
                return constructors.get(0);
            }
        }
        if (selected.getModifiers().contains(Modifier.PRIVATE)) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Generated constructor injection does not support private constructors",
                    selected);
        }
        return selected;
    }

    private DependencyModel dependencyModel(VariableElement parameter) {
        TypeMirror type = parameter.asType();
        if (type.getKind().isPrimitive()) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Constructor dependencies must be bean reference types",
                    parameter);
        }
        String qualifier = annotationString(
                parameter,
                "com.reactor.rust.di.annotation.Qualifier",
                "value",
                "");
        boolean optional = false;
        TypeMirror lookupType = type;
        if (type instanceof DeclaredType declared
                && "java.util.Optional".equals(processingEnv.getTypeUtils().erasure(type).toString())) {
            optional = true;
            if (declared.getTypeArguments().size() != 1) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "Optional injection requires exactly one concrete bean type",
                        parameter);
            } else {
                lookupType = declared.getTypeArguments().getFirst();
                if (lookupType.getKind() == TypeKind.WILDCARD
                        || lookupType.getKind() == TypeKind.TYPEVAR) {
                    processingEnv.getMessager().printMessage(
                            Diagnostic.Kind.ERROR,
                            "Optional injection does not support wildcard or type-variable beans",
                            parameter);
                }
            }
        }
        return new DependencyModel(
                type.toString(),
                processingEnv.getTypeUtils().erasure(lookupType).toString(),
                qualifier,
                optional);
    }

    private void collectBeanMethods(TypeElement type) {
        if (!hasAnnotation(type, CONFIGURATION)) {
            return;
        }
        List<BeanMethodModel> methods = new ArrayList<>();
        boolean fullyResolved = true;
        for (Element enclosed : type.getEnclosedElements()) {
            if (!(enclosed instanceof ExecutableElement method)
                    || enclosed.getKind() != ElementKind.METHOD
                    || !hasAnnotation(method, BEAN)) {
                continue;
            }
            if (method.getModifiers().contains(Modifier.PRIVATE)
                    || method.getModifiers().contains(Modifier.STATIC)) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "Generated @Bean methods must be non-private instance methods",
                        method);
            }
            if (method.getReturnType().getKind() == TypeKind.VOID) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "@Bean method must return a bean",
                        method);
                continue;
            }
            if (method.getReturnType().getKind() == TypeKind.ERROR
                    || method.getParameters().stream()
                    .anyMatch(parameter -> parameter.asType().getKind() == TypeKind.ERROR)) {
                fullyResolved = false;
                continue;
            }
            String beanName = annotationString(method, BEAN, "value", "");
            if (beanName.isBlank()) {
                beanName = method.getSimpleName().toString();
            }
            TypeMirror erased = processingEnv.getTypeUtils().erasure(method.getReturnType());
            Element returnElement = processingEnv.getTypeUtils().asElement(erased);
            methods.add(new BeanMethodModel(
                    method.getSimpleName().toString(),
                    beanName,
                    erased.toString(),
                    returnElement != null && hasAnnotation(returnElement, PRIMARY),
                    method.getParameters().stream().map(this::dependencyModel).toList(),
                    exposedTypes(method.getReturnType()),
                    conditions(method)));
        }
        String typeName = type.getQualifiedName().toString();
        if (fullyResolved) {
            deferredConfigurations.remove(typeName);
            if (!methods.isEmpty()) {
                configurationMethods.put(typeName, List.copyOf(methods));
            }
        } else {
            deferredConfigurations.add(typeName);
        }
    }

    private void retryDeferredConfigurations() {
        if (deferredConfigurations.isEmpty()) {
            return;
        }
        for (String typeName : List.copyOf(deferredConfigurations)) {
            TypeElement type = processingEnv.getElementUtils().getTypeElement(typeName);
            if (type != null) {
                collectBeanMethods(type);
            }
        }
    }

    private void retryDeferredComponents() {
        if (deferredComponents.isEmpty()) {
            return;
        }
        for (String typeName : List.copyOf(deferredComponents)) {
            TypeElement type = processingEnv.getElementUtils().getTypeElement(typeName);
            if (type == null || hasUnresolvedConstructorDependency(type)) {
                continue;
            }
            ComponentAnnotation component = componentAnnotation(type);
            if (component != null) {
                components.put(typeName, componentModel(type, component));
                deferredComponents.remove(typeName);
            }
        }
    }

    private boolean hasUnresolvedConstructorDependency(TypeElement type) {
        List<ExecutableElement> constructors = type.getEnclosedElements().stream()
                .filter(element -> element.getKind() == ElementKind.CONSTRUCTOR)
                .map(ExecutableElement.class::cast)
                .toList();
        ExecutableElement selected = selectConstructor(type, constructors);
        return selected != null && selected.getParameters().stream()
                .anyMatch(parameter -> parameter.asType().getKind() == TypeKind.ERROR);
    }

    private List<String> exposedTypes(TypeElement type) {
        return exposedTypes(type.asType());
    }

    private List<String> exposedTypes(TypeMirror type) {
        Set<String> exposed = new TreeSet<>();
        collectExposedTypes(type, exposed);
        exposed.remove(processingEnv.getTypeUtils().erasure(type).toString());
        exposed.remove(Object.class.getName());
        return List.copyOf(exposed);
    }

    private void collectExposedTypes(TypeMirror type, Set<String> exposed) {
        for (TypeMirror superType : processingEnv.getTypeUtils().directSupertypes(type)) {
            String erased = processingEnv.getTypeUtils().erasure(superType).toString();
            if (!Object.class.getName().equals(erased) && exposed.add(erased)) {
                collectExposedTypes(superType, exposed);
            }
        }
    }

    private void generateComponentFactory(ComponentModel component) {
        TypeElement origin = processingEnv.getElementUtils().getTypeElement(component.type());
        try {
            JavaFileObject source = processingEnv.getFiler().createSourceFile(component.factoryType(), origin);
            try (Writer writer = source.openWriter()) {
                if (!component.packageName().isEmpty()) {
                    writer.write("package " + component.packageName() + ";\n\n");
                }
                writer.write("public final class " + component.factoryName() + " {\n");
                writer.write("    private " + component.factoryName() + "() {}\n\n");
                writer.write("    public static int register(com.reactor.rust.di.BeanContainer container) {\n");
                writeConditionGuard(writer, component.type());
                writer.write("        if (container.hasBean(" + component.type() + ".class)) return 0;\n");
                ConfigurationRecordModel configuration = configurationRecords.get(component.type());
                String construction = configuration == null
                        ? "new " + component.type() + "(" + constructorArguments(component.dependencies()) + ")"
                        : "new " + component.type() + "(" + configurationArguments(configuration) + ")";
                writer.write("        container.registerGeneratedFactory(" + component.type()
                        + ".class, () -> com.reactor.rust.di.GeneratedBeanFactories.create(\""
                        + escape(component.type()) + "\", () -> "
                        + generatedLifecycleFactory(origin, component.type(), construction) + "), \""
                        + escape(component.beanName()) + "\", " + component.primary());
                for (String exposedType : component.exposedTypes()) {
                    writer.write(", " + exposedType + ".class");
                }
                writer.write(");\n");
                if (reflectionFreeGeneratedType(origin)) {
                    writer.write("        container.markGeneratedReflectionFree("
                            + component.type() + ".class);\n");
                } else {
                    writer.write("        container.requireCompatibilitySurface("
                            + component.type() + ".class);\n");
                }
                writer.write("        return 1;\n");
                writer.write("    }\n\n");
                writer.write("    public static int registerHandler(com.reactor.rust.di.BeanContainer container, "
                        + "com.reactor.rust.bridge.HandlerRegistry registry) {\n");
                writer.write("        if (!container.hasBean(" + component.type() + ".class)) return 0;\n");
                writer.write("        registerRouteInvokers();\n");
                writer.write("        int registered = 0;\n");
                if (routeMethods.containsKey(component.type())) {
                    writer.write("        registry.registerBean(container.getBean(" + component.type() + ".class));\n");
                    writer.write("        registered++;\n");
                }
                for (BeanMethodModel method : handlerBeanMethods(component)) {
                    writer.write("        if (container.hasBean(" + method.beanType() + ".class)) {\n");
                    writer.write("            registry.registerBean(container.getBean(" + method.beanType()
                            + ".class));\n");
                    writer.write("            registered++;\n");
                    writer.write("        }\n");
                }
                writer.write("        return registered;\n");
                writer.write("    }\n\n");
                writeExceptionHandlerRegistration(writer, component);
                writeConfigurationBeanRegistration(writer, component);
                writeScheduledTaskRegistration(writer, component);
                writeRouteInvokerRegistration(writer, component);
                writer.write("}\n");
            }
        } catch (IOException failure) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Failed to generate component factory for " + component.type() + ": " + failure.getMessage(),
                    origin);
        }
    }

    private void writeScheduledTaskRegistration(Writer writer, ComponentModel component) throws IOException {
        List<ScheduledMethodModel> methods = scheduledMethods.getOrDefault(component.type(), List.of());
        writer.write("    public static int registerScheduledTasks(com.reactor.rust.di.BeanContainer container) {\n");
        if (methods.isEmpty()) {
            writer.write("        return 0;\n");
            writer.write("    }\n\n");
            return;
        }
        writer.write("        if (!container.hasBean(" + component.type() + ".class)) return 0;\n");
        writer.write("        " + component.type() + " bean = container.getBean(" + component.type() + ".class);\n");
        for (ScheduledMethodModel method : methods) {
            writer.write("        container.getBean(com.reactor.rust.scheduler.ScheduledTaskRegistry.class).register("
                    + javaString(method.name()) + ", bean::" + method.methodName() + ", "
                    + "com.reactor.rust.annotations.Scheduled.Mode." + method.mode() + ", "
                    + method.intervalMs() + "L, " + javaString(method.intervalProperty()) + ", "
                    + method.initialDelayMs() + "L, " + javaString(method.initialDelayProperty()) + ", "
                    + javaString(method.lockName()) + ", " + method.lockAtMostMs() + "L, "
                    + javaString(method.lockAtMostProperty()) + ");\n");
        }
        writer.write("        return " + methods.size() + ";\n");
        writer.write("    }\n\n");
    }

    private String constructorArguments(List<DependencyModel> dependencies) {
        List<String> arguments = new ArrayList<>(dependencies.size());
        for (DependencyModel dependency : dependencies) {
            String lookup = "container.getBean(" + dependency.lookupType() + ".class";
            if (!dependency.qualifier().isBlank()) {
                lookup += ", \"" + escape(dependency.qualifier()) + "\"";
            }
            lookup += ")";
            if (dependency.optional()) {
                String present = dependency.qualifier().isBlank()
                        ? "container.hasBean(" + dependency.lookupType() + ".class)"
                        : "container.hasBean(\"" + escape(dependency.qualifier()) + "\")";
                lookup = present + " ? java.util.Optional.of(" + lookup + ") : java.util.Optional.empty()";
            }
            arguments.add(lookup);
        }
        return String.join(", ", arguments);
    }

    private boolean reflectionFreeGeneratedType(TypeElement type) {
        TypeMirror superType = type.getSuperclass();
        if (superType.getKind() != TypeKind.NONE
                && !Object.class.getName().equals(processingEnv.getTypeUtils().erasure(superType).toString())
                && !"java.lang.Record".equals(processingEnv.getTypeUtils().erasure(superType).toString())) {
            return false;
        }
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.FIELD
                    && (hasAnnotation(enclosed, AUTOWIRED) || hasAnnotation(enclosed, RUST_PROPERTY))) {
                return false;
            }
        }
        return true;
    }

    private String generatedLifecycleFactory(
            TypeElement type,
            String typeName,
            String construction) {
        List<ExecutableElement> postConstruct = lifecycleMethods(type, POST_CONSTRUCT);
        List<ExecutableElement> preDestroy = lifecycleMethods(type, PRE_DESTROY);
        if (postConstruct.isEmpty() && preDestroy.isEmpty()) {
            return construction;
        }
        StringBuilder source = new StringBuilder(192);
        source.append("{ ").append(typeName).append(" bean = ").append(construction).append("; ");
        for (ExecutableElement method : postConstruct) {
            source.append("bean.").append(method.getSimpleName()).append("(); ");
        }
        for (ExecutableElement method : preDestroy) {
            source.append("container.registerGeneratedPreDestroy(bean::")
                    .append(method.getSimpleName()).append("); ");
        }
        return source.append("return bean; }").toString();
    }

    private List<ExecutableElement> lifecycleMethods(TypeElement type, String annotationType) {
        List<ExecutableElement> methods = new ArrayList<>();
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD || !hasAnnotation(enclosed, annotationType)) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) enclosed;
            boolean valid = method.getParameters().isEmpty()
                    && method.getReturnType().getKind() == TypeKind.VOID
                    && !method.getModifiers().contains(Modifier.PRIVATE)
                    && !method.getModifiers().contains(Modifier.STATIC);
            if (!valid) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "Generated lifecycle methods must be non-private instance void methods with no parameters",
                        method);
                continue;
            }
            methods.add(method);
        }
        return methods;
    }

    private String configurationArguments(ConfigurationRecordModel configuration) {
        return configuration.properties().stream()
                .map(ConfigurationPropertyModel::expression)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private void writeConditionGuard(Writer writer, String componentType) throws IOException {
        ConditionsModel conditions = componentConditions.getOrDefault(
                componentType,
                new ConditionsModel(List.of(), List.of()));
        String expression = conditionExpression(conditions);
        if (!expression.isEmpty()) {
            writer.write("        if (!(" + expression + ")) return 0;\n");
        }
    }

    private String conditionExpression(ConditionsModel conditions) {
        List<String> checks = new ArrayList<>();
        for (PropertyConditionModel property : conditions.properties()) {
            checks.add("com.reactor.rust.config.ConfigurationBinder.matches("
                    + javaString(property.name()) + ", " + javaString(property.value()) + ", "
                    + property.matchIfMissing() + ")");
        }
        if (!conditions.profiles().isEmpty()) {
            String profiles = conditions.profiles().stream()
                    .map(ReactorStartupProcessor::javaString)
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("");
            checks.add("com.reactor.rust.config.ConfigurationBinder.profileMatches(" + profiles + ")");
        }
        return String.join(" && ", checks);
    }

    private RouteMethodModel routeMethodModel(
            ExecutableElement method,
            AnnotationMirror routeAnnotation,
            String httpMethod,
            String path) {
        if (method.getModifiers().contains(Modifier.PRIVATE)
                || method.getModifiers().contains(Modifier.STATIC)) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Generated routes must be non-private instance methods",
                    method);
        }
        if (method.getParameters().size() > 8) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Generated routes support at most 8 parameters; use a request DTO",
                    method);
        }
        List<RouteParameterModel> parameters = method.getParameters().stream()
                .map(parameter -> new RouteParameterModel(
                        parameter.asType().toString(),
                        processingEnv.getTypeUtils().erasure(parameter.asType()).toString(),
                        parameter.asType().getKind()))
                .toList();
        TypeMirror requestType = inferredRequestType(method, routeAnnotation);
        TypeMirror responseType = inferredResponseType(method, routeAnnotation);
        AnnotationMirror maxRequest = annotation(method, MAX_REQUEST_BODY_SIZE);
        AnnotationMirror maxResponse = annotation(method, MAX_RESPONSE_SIZE);
        return new RouteMethodModel(
                method.getSimpleName().toString(),
                parameters,
                method.getReturnType().getKind() == TypeKind.VOID,
                generatedPrimitiveBinding(method),
                httpMethod,
                path,
                processingEnv.getTypeUtils().erasure(requestType).toString(),
                processingEnv.getTypeUtils().erasure(responseType).toString(),
                maxRequest == null ? 0L : annotationLong(maxRequest, "value", 0L),
                maxResponse == null ? 0L : annotationLong(maxResponse, "value", 0L));
    }

    private TypeMirror inferredRequestType(
            ExecutableElement method,
            AnnotationMirror routeAnnotation) {
        TypeMirror declared = annotationType(routeAnnotation, "requestType");
        if (!isVoidType(declared)) {
            return declared;
        }
        for (VariableElement parameter : method.getParameters()) {
            if (annotation(parameter, REQUEST_BODY) != null) {
                return parameter.asType();
            }
        }
        return processingEnv.getElementUtils().getTypeElement("java.lang.Void").asType();
    }

    private TypeMirror inferredResponseType(
            ExecutableElement method,
            AnnotationMirror routeAnnotation) {
        TypeMirror declared = annotationType(routeAnnotation, "responseType");
        if (!isVoidType(declared)) {
            return declared;
        }
        TypeMirror inferred = unwrapResponseType(method.getReturnType());
        return inferred != null
                ? inferred
                : processingEnv.getElementUtils().getTypeElement("java.lang.Void").asType();
    }

    private boolean isVoidType(TypeMirror type) {
        if (type == null || type.getKind() == TypeKind.VOID) {
            return true;
        }
        return processingEnv.getTypeUtils().erasure(type).toString().equals("java.lang.Void");
    }

    private PrimitiveBindingModel generatedPrimitiveBinding(ExecutableElement method) {
        if (method.getParameters().size() != 1 || isCompletionStage(method.getReturnType())) {
            return null;
        }
        VariableElement parameter = method.getParameters().get(0);
        String kind = switch (parameter.asType().getKind()) {
            case INT -> "INT";
            case LONG -> "LONG";
            case BOOLEAN -> "BOOLEAN";
            case DOUBLE -> "DOUBLE";
            case SHORT -> "SHORT";
            default -> null;
        };
        if (kind == null) return null;

        AnnotationMirror requestParam = annotation(parameter, REQUEST_PARAM);
        AnnotationMirror pathVariable = annotation(parameter, PATH_VARIABLE);
        if (requestParam == null && pathVariable == null) return null;
        if (requestParam != null && pathVariable != null) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "A generated primitive parameter cannot be both @RequestParam and @PathVariable",
                    parameter);
            return null;
        }
        if (pathVariable != null) {
            return new PrimitiveBindingModel(
                    "PATH",
                    kind,
                    annotationString(pathVariable, "value", ""),
                    "",
                    "STRICT_REQUIRED");
        }

        String name = annotationString(requestParam, "value", "");
        String defaultValue = annotationString(requestParam, "defaultValue", "");
        boolean required = annotationBoolean(requestParam, "required", true);
        if (defaultValue.isEmpty() && !required) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Optional primitive @RequestParam requires a defaultValue or a boxed parameter type",
                    parameter);
            return null;
        }
        if (!defaultValue.isEmpty() && !validPrimitiveDefault(kind, defaultValue)) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Invalid " + kind.toLowerCase(Locale.ROOT)
                            + " @RequestParam defaultValue: " + defaultValue,
                    parameter);
            return null;
        }
        return new PrimitiveBindingModel(
                "QUERY",
                kind,
                name,
                defaultValue,
                defaultValue.isEmpty() ? "STRICT_REQUIRED" : "STRICT_DEFAULT");
    }

    private boolean isCompletionStage(TypeMirror returnType) {
        TypeElement completionStage = processingEnv.getElementUtils()
                .getTypeElement("java.util.concurrent.CompletionStage");
        return completionStage != null && processingEnv.getTypeUtils().isAssignable(
                processingEnv.getTypeUtils().erasure(returnType),
                processingEnv.getTypeUtils().erasure(completionStage.asType()));
    }

    private static boolean validPrimitiveDefault(String kind, String value) {
        try {
            return switch (kind) {
                case "INT" -> { Integer.parseInt(value); yield true; }
                case "LONG" -> { Long.parseLong(value); yield true; }
                case "SHORT" -> { Short.parseShort(value); yield true; }
                case "DOUBLE" -> Double.isFinite(Double.parseDouble(value));
                case "BOOLEAN" -> "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
                default -> false;
            };
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static String methodSignature(ExecutableElement method) {
        StringBuilder signature = new StringBuilder(method.getSimpleName()).append('(');
        for (VariableElement parameter : method.getParameters()) {
            signature.append(parameter.asType()).append(';');
        }
        return signature.append(')').toString();
    }

    private void writeConfigurationBeanRegistration(Writer writer, ComponentModel component) throws IOException {
        List<BeanMethodModel> methods = configurationMethods.getOrDefault(component.type(), List.of());
        writer.write("    public static int registerConfigurationBeans("
                + "com.reactor.rust.di.BeanContainer container) {\n");
        if (methods.isEmpty()) {
            writer.write("        return 0;\n");
            writer.write("    }\n\n");
            return;
        }
        writer.write("        if (!container.hasBean(" + component.type() + ".class)) return 0;\n");
        writer.write("        " + component.type() + " configuration = container.getBean("
                + component.type() + ".class);\n");
        writer.write("        int registered = 0;\n");
        for (BeanMethodModel method : methods) {
            String condition = conditionExpression(method.conditions());
            writer.write("        if ("
                    + (condition.isEmpty() ? "" : "(" + condition + ") && ")
                    + "!container.hasBean(\"" + escape(method.beanName()) + "\")) {\n");
            writer.write("            container.registerGeneratedFactory(" + method.beanType()
                    + ".class, () -> com.reactor.rust.di.GeneratedBeanFactories.create(\""
                    + escape(component.type() + "#" + method.methodName()) + "\", () -> configuration."
                    + method.methodName() + "(" + constructorArguments(method.dependencies()) + ")), \""
                    + escape(method.beanName()) + "\", " + method.primary());
            for (String exposedType : method.exposedTypes()) {
                writer.write(", " + exposedType + ".class");
            }
            writer.write(");\n");
            writer.write("            registered++;\n");
            writer.write("        }\n");
        }
        writer.write("        container.markGeneratedConfiguration(configuration);\n");
        writer.write("        return registered;\n");
        writer.write("    }\n\n");
    }

    private void writeExceptionHandlerRegistration(Writer writer, ComponentModel component) throws IOException {
        writer.write("    public static int registerExceptionHandlers("
                + "com.reactor.rust.di.BeanContainer container, "
                + "com.reactor.rust.exception.ExceptionHandlerRegistry registry) {\n");
        if (!hasExceptionHandlerSurface(component)) {
            writer.write("        return 0;\n");
            writer.write("    }\n\n");
            return;
        }
        writer.write("        int registered = 0;\n");
        if (exceptionHandlerMethods.containsKey(component.type())) {
            writer.write("        if (container.hasBean(" + component.type() + ".class)) {\n");
            writeOwnerExceptionHandlers(writer, component.type(), "            ");
            writer.write("        }\n");
        }
        for (BeanMethodModel method : exceptionHandlerBeanMethods(component)) {
            writer.write("        if (container.hasBean(" + method.beanType() + ".class)) {\n");
            writeOwnerExceptionHandlers(writer, method.beanType(), "            ");
            writer.write("        }\n");
        }
        writer.write("        return registered;\n");
        writer.write("    }\n\n");
    }

    private void writeOwnerExceptionHandlers(
            Writer writer,
            String ownerType,
            String indent) throws IOException {
        for (ExceptionHandlerMethodModel method :
                exceptionHandlerMethods.getOrDefault(ownerType, Map.of()).values()) {
            for (String exceptionType : method.exceptionTypes()) {
                writer.write(indent + "registry.registerGenerated(container.getBean(" + ownerType
                        + ".class), " + exceptionType
                        + ".class, (bean, error) -> {\n");
                String invocation = "((" + ownerType + ") bean)." + method.name() + "("
                        + (method.parameterType() == null
                        ? ""
                        : "(" + method.parameterType() + ") error")
                        + ")";
                if (method.returnsVoid()) {
                    writer.write(indent + "    " + invocation + ";\n");
                    writer.write(indent + "    return null;\n");
                } else {
                    writer.write(indent + "    return " + invocation + ";\n");
                }
                writer.write(indent + "});\n");
                writer.write(indent + "registered++;\n");
            }
        }
    }

    private void writeRouteInvokerRegistration(Writer writer, ComponentModel component) throws IOException {
        writer.write("    private static void registerRouteInvokers() {\n");
        writeOwnerRouteInvokers(writer, component.type());
        for (BeanMethodModel method : handlerBeanMethods(component)) {
            writeOwnerRouteInvokers(writer, method.beanType());
        }
        writer.write("    }\n");
    }

    private void writeOwnerRouteInvokers(Writer writer, String ownerType) throws IOException {
        List<RouteMethodModel> methods = new ArrayList<>(
                routeMethods.getOrDefault(ownerType, Map.of()).values());
        for (RouteMethodModel method : methods) {
            writer.write("        com.reactor.rust.bridge.GeneratedRouteInvokers.register("
                    + ownerType + ".class, \"" + escape(method.name()) + "\", new Class<?>[]{");
            for (int index = 0; index < method.parameters().size(); index++) {
                if (index > 0) writer.write(", ");
                writer.write(method.parameters().get(index).lookupType() + ".class");
            }
            writer.write("}, new com.reactor.rust.bridge.GeneratedRouteInvoker() {\n");
            writer.write("            @Override public int arity() { return "
                    + method.parameters().size() + "; }\n");
            writer.write("            @Override public Object invoke" + method.parameters().size()
                    + "(Object bean");
            for (int index = 0; index < method.parameters().size(); index++) {
                writer.write(", Object arg" + index);
            }
            writer.write(") throws Throwable {\n");
            String invocation = "((" + ownerType + ") bean)." + method.name()
                    + "(" + routeArguments(method.parameters()) + ")";
            if (method.returnsVoid()) {
                writer.write("                " + invocation + ";\n");
                writer.write("                return null;\n");
            } else {
                writer.write("                return " + invocation + ";\n");
            }
            writer.write("            }\n");
            PrimitiveBindingModel binding = method.primitiveBinding();
            if (binding != null) {
                writer.write("            @Override public Object invoke" + primitiveMethod(binding.kind())
                        + "(Object bean, " + primitiveJavaType(binding.kind()) + " value) throws Throwable {\n");
                String primitiveInvocation = "((" + ownerType + ") bean)." + method.name() + "(value)";
                if (method.returnsVoid()) {
                    writer.write("                " + primitiveInvocation + ";\n");
                    writer.write("                return null;\n");
                } else {
                    writer.write("                return " + primitiveInvocation + ";\n");
                }
                writer.write("            }\n");
            }
            writer.write("        }, new com.reactor.rust.bridge.GeneratedRouteMetadata("
                    + javaString(method.httpMethod()) + ", "
                    + javaString(method.path()) + ", "
                    + method.requestType() + ".class, "
                    + method.responseType() + ".class, "
                    + method.maxRequestBodyBytes() + "L, "
                    + method.maxResponseBodyBytes() + "L));\n");
            if (binding != null) {
                writer.write("        com.reactor.rust.bridge.GeneratedPrimitiveBindings.register("
                        + ownerType + ".class, \"" + escape(method.name()) + "\", new Class<?>[]{");
                for (int index = 0; index < method.parameters().size(); index++) {
                    if (index > 0) writer.write(", ");
                    writer.write(method.parameters().get(index).lookupType() + ".class");
                }
                writer.write("}, new com.reactor.rust.bridge.GeneratedPrimitiveBinding("
                        + "com.reactor.rust.bridge.GeneratedPrimitiveBinding.Source." + binding.source() + ", "
                        + "com.reactor.rust.bridge.GeneratedPrimitiveBinding.Kind." + binding.kind() + ", \""
                        + escape(binding.name()) + "\", \"" + escape(binding.defaultValue()) + "\", "
                        + "com.reactor.rust.bridge.GeneratedPrimitiveBinding.Mode." + binding.mode() + "));\n");
            }
        }
    }

    private static String primitiveMethod(String kind) {
        return switch (kind) {
            case "INT" -> "Int";
            case "LONG" -> "Long";
            case "BOOLEAN" -> "Boolean";
            case "DOUBLE" -> "Double";
            case "SHORT" -> "Short";
            default -> throw new IllegalArgumentException("Unsupported generated primitive kind " + kind);
        };
    }

    private static String primitiveJavaType(String kind) {
        return kind.toLowerCase(Locale.ROOT);
    }

    private boolean hasHandlerSurface(ComponentModel component) {
        return routeMethods.containsKey(component.type()) || !handlerBeanMethods(component).isEmpty();
    }

    private boolean hasExceptionHandlerSurface(ComponentModel component) {
        return exceptionHandlerMethods.containsKey(component.type())
                || !exceptionHandlerBeanMethods(component).isEmpty();
    }

    private List<BeanMethodModel> handlerBeanMethods(ComponentModel component) {
        return configurationMethods.getOrDefault(component.type(), List.of()).stream()
                .filter(method -> routeMethods.containsKey(method.beanType()))
                .toList();
    }

    private List<BeanMethodModel> exceptionHandlerBeanMethods(ComponentModel component) {
        return configurationMethods.getOrDefault(component.type(), List.of()).stream()
                .filter(method -> exceptionHandlerMethods.containsKey(method.beanType()))
                .toList();
    }

    private String routeArguments(List<RouteParameterModel> parameters) {
        List<String> arguments = new ArrayList<>(parameters.size());
        for (int index = 0; index < parameters.size(); index++) {
            RouteParameterModel parameter = parameters.get(index);
            String value = switch (parameter.kind()) {
                case BOOLEAN -> "((Boolean) arg" + index + ").booleanValue()";
                case BYTE -> "((Byte) arg" + index + ").byteValue()";
                case SHORT -> "((Short) arg" + index + ").shortValue()";
                case INT -> "((Integer) arg" + index + ").intValue()";
                case LONG -> "((Long) arg" + index + ").longValue()";
                case CHAR -> "((Character) arg" + index + ").charValue()";
                case FLOAT -> "((Float) arg" + index + ").floatValue()";
                case DOUBLE -> "((Double) arg" + index + ").doubleValue()";
                default -> "(" + parameter.declaredType() + ") arg" + index;
            };
            arguments.add(value);
        }
        return String.join(", ", arguments);
    }

    private boolean hasAnnotation(Element element, String qualifiedName) {
        return annotation(element, qualifiedName) != null;
    }

    private AnnotationMirror annotation(Element element, String qualifiedName) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (mirror.getAnnotationType().toString().equals(qualifiedName)) {
                return mirror;
            }
        }
        return null;
    }

    private String annotationString(Element element, String annotationName, String key, String fallback) {
        AnnotationMirror mirror = annotation(element, annotationName);
        return mirror == null ? fallback : annotationString(mirror, key, fallback);
    }

    private String annotationString(AnnotationMirror mirror, String key, String fallback) {
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

    private boolean annotationBoolean(
            AnnotationMirror mirror,
            String key,
            boolean fallback) {
        Object value = annotationValue(mirror, key);
        return value instanceof Boolean bool ? bool : fallback;
    }

    private List<String> annotationStrings(AnnotationMirror mirror, String key) {
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

    private List<AnnotationMirror> annotationMirrors(AnnotationMirror mirror, String key) {
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

    private List<TypeMirror> annotationTypes(AnnotationMirror mirror, String key) {
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

    private TypeMirror annotationType(AnnotationMirror mirror, String key) {
        Object value = annotationValue(mirror, key);
        return value instanceof TypeMirror type ? type : null;
    }

    private Object annotationValue(AnnotationMirror mirror, String key) {
        Map<? extends ExecutableElement, ? extends AnnotationValue> values =
                processingEnv.getElementUtils().getElementValuesWithDefaults(mirror);
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : values.entrySet()) {
            if (entry.getKey().getSimpleName().contentEquals(key)) {
                return entry.getValue().getValue();
            }
        }
        return null;
    }

    private static String combine(String basePath, String methodPath) {
        String base = normalizePath(basePath, false);
        String method = normalizePath(methodPath, true);
        if (base.isEmpty()) {
            return method;
        }
        return "/".equals(method) ? base : base + method;
    }

    private static String normalizePath(String value, boolean rootWhenEmpty) {
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

    private static String beanName(String simpleName) {
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }

    private static String kebabCase(String value) {
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

    private static String javaString(String value) {
        return value == null ? "null" : "\"" + escape(value) + "\"";
    }

    private static String jsonEscape(String value) {
        return value == null ? "" : escape(value);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private record ComponentAnnotation(String name) {}

    private record ComponentModel(
            String type,
            String packageName,
            String factoryName,
            String factoryType,
            String beanName,
            boolean primary,
            List<DependencyModel> dependencies,
            List<String> exposedTypes) {}

    private record ConfigurationRecordModel(
            String prefix,
            List<ConfigurationPropertyModel> properties) {}

    private record ConfigurationPropertyModel(
            String key,
            String type,
            String defaultValue,
            String expression) {}

    private record ConditionsModel(
            List<PropertyConditionModel> properties,
            List<String> profiles) {}

    private record PropertyConditionModel(
            String name,
            String value,
            boolean matchIfMissing) {}

    private record DependencyModel(
            String declaredType,
            String lookupType,
            String qualifier,
            boolean optional) {}

    private record RouteMethodModel(
            String name,
            List<RouteParameterModel> parameters,
            boolean returnsVoid,
            PrimitiveBindingModel primitiveBinding,
            String httpMethod,
            String path,
            String requestType,
            String responseType,
            long maxRequestBodyBytes,
            long maxResponseBodyBytes) {}

    private record RouteParameterModel(String declaredType, String lookupType, TypeKind kind) {}

    private record ExceptionHandlerMethodModel(
            String name,
            String parameterType,
            List<String> exceptionTypes,
            boolean returnsVoid) {}

    private record ScheduledMethodModel(
            String methodName,
            String name,
            String mode,
            long intervalMs,
            String intervalProperty,
            long initialDelayMs,
            String initialDelayProperty,
            String lockName,
            long lockAtMostMs,
            String lockAtMostProperty) {}

    private record HttpClientModel(
            String type,
            String packageName,
            String generatedName,
            String generatedType,
            String beanName,
            String baseUrlProperty,
            List<HttpClientMethodModel> methods,
            TypeElement origin) {}

    private record HttpClientMethodModel(
            String name,
            String returnType,
            String responseType,
            String responseKind,
            String httpMethod,
            String path,
            String contentType,
            String accept,
            long timeoutMs,
            int retries,
            boolean idempotent,
            boolean responseWrapper,
            List<HttpClientParameterModel> parameters) {}

    private record HttpClientParameterModel(
            String type,
            int index,
            String kind,
            String name,
            boolean optional) {}

    private record PrimitiveBindingModel(
            String source,
            String kind,
            String name,
            String defaultValue,
            String mode) {}

    private record OpenApiRouteModel(
            String method,
            String path,
            String operationId,
            String summary,
            String description,
            List<String> tags,
            List<OpenApiParameterModel> parameters,
            TypeMirror requestBody,
            boolean requestBodyRequired,
            List<OpenApiResponseModel> responses) {}

    private record OpenApiParameterModel(
            String name,
            String location,
            boolean required,
            String defaultValue,
            TypeMirror type) {}

    private record OpenApiResponseModel(int status, String description, TypeMirror body) {}

    private record BeanMethodModel(
            String methodName,
            String beanName,
            String beanType,
            boolean primary,
            List<DependencyModel> dependencies,
            List<String> exposedTypes,
            ConditionsModel conditions) {}
}
