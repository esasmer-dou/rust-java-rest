package com.reactor.rust.codegen;

import javax.lang.model.element.AnnotationMirror;
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
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static com.reactor.rust.codegen.ComponentGraphScanner.ConditionsModel;
import static com.reactor.rust.codegen.ReactorStartupProcessor.*;

/** Collects route models and emits the OpenAPI description. */
final class RouteModelCollector {

    private final ReactorStartupProcessor processor;

    RouteModelCollector(ReactorStartupProcessor processor) {
        this.processor = processor;
    }

    final Map<String, String> routes = new TreeMap<>();
    final Map<String, OpenApiRouteModel> openApiRoutes = new TreeMap<>();
    final Set<String> routeOwners = new TreeSet<>();
    final Map<String, Map<String, RouteMethodModel>> routeMethods = new TreeMap<>();
    final Map<String, Map<String, ExceptionHandlerMethodModel>> exceptionHandlerMethods =
            new TreeMap<>();
    String applicationType;
    String openApiTitle = "Reactor Application";
    String openApiVersion = "1.0.0";
    String openApiDescription = "";

    void collectApplicationMetadata(TypeElement type) {
        AnnotationMirror application = processor.annotation(type, REACTOR_APPLICATION);
        if (application == null) return;
        String qualifiedName = type.getQualifiedName().toString();
        if (applicationType != null && !applicationType.equals(qualifiedName)) {
            processor.environment().getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Only one @ReactorApplication entry point may be compiled per application module",
                    type);
            return;
        }
        applicationType = qualifiedName;
        String configuredName = processor.annotationString(application, "name", "").trim();
        openApiTitle = configuredName.isEmpty() ? type.getSimpleName().toString() : configuredName;
        String configuredVersion = processor.environment().getOptions().getOrDefault("reactor.application.version",
                processor.annotationString(application, "version", "1.0.0")).trim();
        openApiVersion = configuredVersion.isEmpty() ? "1.0.0" : configuredVersion;
        openApiDescription = processor.annotationString(application, "description", "").trim();
    }

    void collectExceptionHandler(TypeElement owner, ExecutableElement method) {
        AnnotationMirror handler = processor.annotation(method, EXCEPTION_HANDLER);
        if (handler == null) return;
        if (method.getModifiers().contains(Modifier.PRIVATE)
                || method.getModifiers().contains(Modifier.STATIC)) {
            processor.environment().getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Generated exception handlers must be non-private instance methods",
                    method);
            return;
        }
        if (method.getParameters().size() > 1) {
            processor.environment().getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@ExceptionHandler methods accept zero or one Throwable parameter",
                    method);
            return;
        }

        TypeMirror throwable = processor.environment().getElementUtils()
                .getTypeElement(Throwable.class.getName()).asType();
        String parameterType = null;
        if (!method.getParameters().isEmpty()) {
            TypeMirror parameter = method.getParameters().get(0).asType();
            if (!processor.environment().getTypeUtils().isAssignable(parameter, throwable)) {
                processor.environment().getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "@ExceptionHandler parameter must extend Throwable",
                        method);
                return;
            }
            parameterType = parameter.toString();
        }

        List<TypeMirror> declaredTypes = processor.annotationTypes(handler, "value");
        List<String> exceptionTypes = new ArrayList<>();
        if (declaredTypes.isEmpty()) {
            exceptionTypes.add(parameterType == null ? Throwable.class.getName() : parameterType);
        } else {
            TypeMirror parameter = method.getParameters().isEmpty()
                    ? null
                    : method.getParameters().get(0).asType();
            for (TypeMirror declaredType : declaredTypes) {
                if (!processor.environment().getTypeUtils().isAssignable(declaredType, throwable)) {
                    processor.environment().getMessager().printMessage(
                            Diagnostic.Kind.ERROR,
                            "@ExceptionHandler value must extend Throwable: " + declaredType,
                            method);
                    continue;
                }
                if (parameter != null && !processor.environment().getTypeUtils().isAssignable(declaredType, parameter)) {
                    processor.environment().getMessager().printMessage(
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
                .putIfAbsent(processor.methodSignature(method), model);
    }

    void collectRoute(TypeElement owner, ExecutableElement method, String basePath) {
        String httpMethod = null;
        String path = null;
        AnnotationMirror rustRoute = processor.annotation(method, RUST_ROUTE);
        AnnotationMirror routeAnnotation = rustRoute;
        if (rustRoute != null) {
            httpMethod = processor.annotationString(rustRoute, "method", "GET").toUpperCase(Locale.ROOT);
            path = processor.annotationString(rustRoute, "path", "/");
        } else {
            for (Map.Entry<String, String> mapping : MAPPINGS.entrySet()) {
                AnnotationMirror route = processor.annotation(method, mapping.getKey());
                if (route != null) {
                    routeAnnotation = route;
                    httpMethod = mapping.getValue();
                    path = processor.annotationString(route, "value", "");
                    break;
                }
            }
        }
        if (httpMethod == null) {
            if (processor.hasAnnotation(method, "com.reactor.rust.annotations.KeyAdmission")) {
                keyAdmissionError("@KeyAdmission requires an HTTP route mapping", method);
            }
            return;
        }
        String fullPath = processor.combine(basePath, path);
        String key = httpMethod + " " + fullPath;
        String line = key + " " + owner.getQualifiedName() + "#" + method.getSimpleName();
        String previous = routes.putIfAbsent(key, line);
        if (previous != null && !previous.equals(line)) {
            processor.environment().getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Duplicate generated route " + key + " owners=[" + previous + ", " + line + "]",
                    method);
        }
        routeOwners.add(owner.getQualifiedName().toString());
        openApiRoutes.putIfAbsent(key, openApiRouteModel(owner, method, httpMethod, fullPath));
        routeMethods.computeIfAbsent(owner.getQualifiedName().toString(), ignored -> new TreeMap<>())
                .putIfAbsent(processor.methodSignature(method), routeMethodModel(
                        method, routeAnnotation, httpMethod, fullPath));
    }

    OpenApiRouteModel openApiRouteModel(
            TypeElement owner,
            ExecutableElement method,
            String httpMethod,
            String path) {
        AnnotationMirror operation = processor.annotation(method, OPENAPI_OPERATION);
        String defaultOperationId = owner.getSimpleName() + "_" + method.getSimpleName();
        String operationId = operation == null
                ? defaultOperationId
                : processor.annotationString(operation, "operationId", defaultOperationId);
        if (operationId.isBlank()) operationId = defaultOperationId;
        String summary = operation == null ? "" : processor.annotationString(operation, "summary", "");
        String description = operation == null ? "" : processor.annotationString(operation, "description", "");
        List<String> tags = operation == null ? List.of() : processor.annotationStrings(operation, "tags");

        List<OpenApiParameterModel> parameters = new ArrayList<>();
        TypeMirror requestBody = null;
        boolean requestBodyRequired = false;
        for (VariableElement parameter : method.getParameters()) {
            AnnotationMirror body = processor.annotation(parameter, REQUEST_BODY);
            if (body != null) {
                requestBody = parameter.asType();
                requestBodyRequired = processor.annotationBoolean(body, "required", true);
                continue;
            }
            AnnotationMirror pathVariable = processor.annotation(parameter, PATH_VARIABLE);
            AnnotationMirror requestParam = processor.annotation(parameter, REQUEST_PARAM);
            AnnotationMirror header = processor.annotation(parameter, HEADER_PARAM);
            AnnotationMirror cookie = processor.annotation(parameter, COOKIE_VALUE);
            if (pathVariable != null) {
                parameters.add(new OpenApiParameterModel(
                        processor.annotationString(pathVariable, "value", parameter.getSimpleName().toString()),
                        "path", true, "", parameter.asType()));
            } else if (requestParam != null) {
                String defaultValue = processor.annotationString(requestParam, "defaultValue", "");
                parameters.add(new OpenApiParameterModel(
                        processor.annotationString(requestParam, "value", parameter.getSimpleName().toString()),
                        "query",
                        processor.annotationBoolean(requestParam, "required", true) && defaultValue.isEmpty(),
                        defaultValue,
                        parameter.asType()));
            } else if (header != null) {
                String defaultValue = processor.annotationString(header, "defaultValue", "");
                parameters.add(new OpenApiParameterModel(
                        processor.annotationString(header, "value", parameter.getSimpleName().toString()),
                        "header",
                        processor.annotationBoolean(header, "required", false) && defaultValue.isEmpty(),
                        defaultValue,
                        parameter.asType()));
            } else if (cookie != null) {
                String defaultValue = processor.annotationString(cookie, "defaultValue", "");
                parameters.add(new OpenApiParameterModel(
                        processor.annotationString(cookie, "value", parameter.getSimpleName().toString()),
                        "cookie",
                        processor.annotationBoolean(cookie, "required", false) && defaultValue.isEmpty(),
                        defaultValue,
                        parameter.asType()));
            }
        }

        List<OpenApiResponseModel> responses = documentedResponses(method);
        int successStatus = 200;
        AnnotationMirror responseStatus = processor.annotation(method, "com.reactor.rust.annotations.ResponseStatus");
        if (responseStatus != null) successStatus = processor.annotationInt(responseStatus, "value", 200);
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

    List<OpenApiResponseModel> documentedResponses(ExecutableElement method) {
        List<OpenApiResponseModel> responses = new ArrayList<>();
        AnnotationMirror direct = processor.annotation(method, OPENAPI_RESPONSE);
        if (direct != null) responses.add(openApiResponse(direct));
        AnnotationMirror container = processor.annotation(method, OPENAPI_RESPONSES);
        if (container != null) {
            for (AnnotationMirror nested : processor.annotationMirrors(container, "value")) {
                responses.add(openApiResponse(nested));
            }
        }
        return responses;
    }

    OpenApiResponseModel openApiResponse(AnnotationMirror response) {
        TypeMirror body = processor.annotationType(response, "body");
        if (body != null && "java.lang.Void".equals(body.toString())) body = null;
        return new OpenApiResponseModel(
                processor.annotationInt(response, "status", 200),
                processor.annotationString(response, "description", "Response"),
                body);
    }

    TypeMirror unwrapResponseType(TypeMirror type) {
        TypeMirror current = type;
        while (current instanceof DeclaredType declared && !declared.getTypeArguments().isEmpty()) {
            String raw = processor.environment().getTypeUtils().erasure(current).toString();
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

    List<String> conditionMetadata() {
        List<String> metadata = new ArrayList<>();
        for (Map.Entry<String, ConditionsModel> entry : processor.graph.componentConditions.entrySet()) {
            ConditionsModel conditions = entry.getValue();
            if (!conditions.properties().isEmpty() || !conditions.profiles().isEmpty()) {
                metadata.add(entry.getKey());
            }
        }
        return metadata;
    }

    List<String> healthRouteMetadata() {
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

    void writeOpenApiDocument() {
        if (openApiRoutes.isEmpty()) return;
        Map<String, List<OpenApiRouteModel>> paths = new TreeMap<>();
        for (OpenApiRouteModel route : openApiRoutes.values()) {
            paths.computeIfAbsent(route.path(), ignored -> new ArrayList<>()).add(route);
        }
        try {
            FileObject resource = processor.environment().getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT, "", "META-INF/reactor/openapi.json");
            try (Writer writer = resource.openWriter()) {
                writer.write("{\"openapi\":\"3.1.0\",\"info\":{\"title\":\""
                        + processor.jsonEscape(openApiTitle) + "\",\"version\":\""
                        + processor.jsonEscape(openApiVersion) + "\"");
                if (!openApiDescription.isEmpty()) {
                    writer.write(",\"description\":\"" + processor.jsonEscape(openApiDescription) + "\"");
                }
                writer.write("},\"paths\":{");
                int pathIndex = 0;
                for (Map.Entry<String, List<OpenApiRouteModel>> path : paths.entrySet()) {
                    if (pathIndex++ > 0) writer.write(",");
                    writer.write("\"" + processor.jsonEscape(path.getKey()) + "\":{");
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
                for (Map.Entry<String, TypeElement> schema : processor.validation.validationTypes.entrySet()) {
                    writer.write(",\"" + processor.jsonEscape(schemaName(schema.getKey())) + "\":");
                    writeRecordSchema(writer, schema.getValue());
                }
                writer.write("}}}");
            }
        } catch (IOException failure) {
            processor.environment().getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Failed to generate OpenAPI document: " + failure.getMessage());
        }
    }

    void writeOpenApiOperation(Writer writer, OpenApiRouteModel operation) throws IOException {
        writer.write("\"" + operation.method() + "\":{\"operationId\":\""
                + processor.jsonEscape(operation.operationId()) + "\"");
        if (!operation.summary().isBlank()) {
            writer.write(",\"summary\":\"" + processor.jsonEscape(operation.summary()) + "\"");
        }
        if (!operation.description().isBlank()) {
            writer.write(",\"description\":\"" + processor.jsonEscape(operation.description()) + "\"");
        }
        if (!operation.tags().isEmpty()) {
            writer.write(",\"tags\":[");
            for (int index = 0; index < operation.tags().size(); index++) {
                if (index > 0) writer.write(",");
                writer.write("\"" + processor.jsonEscape(operation.tags().get(index)) + "\"");
            }
            writer.write("]");
        }
        if (!operation.parameters().isEmpty()) {
            writer.write(",\"parameters\":[");
            for (int index = 0; index < operation.parameters().size(); index++) {
                if (index > 0) writer.write(",");
                OpenApiParameterModel parameter = operation.parameters().get(index);
                writer.write("{\"name\":\"" + processor.jsonEscape(parameter.name()) + "\",\"in\":\""
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
                    + processor.jsonEscape(response.description()) + "\"");
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

    void writeRecordSchema(Writer writer, TypeElement type) throws IOException {
        writer.write("{\"type\":\"object\",\"properties\":{");
        List<? extends RecordComponentElement> components = type.getRecordComponents();
        List<String> required = new ArrayList<>();
        for (int index = 0; index < components.size(); index++) {
            if (index > 0) writer.write(",");
            RecordComponentElement component = components.get(index);
            writer.write("\"" + processor.jsonEscape(component.getSimpleName().toString()) + "\":"
                    + schemaJson(component.asType(), ""));
            if (component.asType().getKind().isPrimitive()
                    || processor.validation.validationAnnotation(type, component, NOT_NULL) != null
                    || processor.validation.validationAnnotation(type, component, NOT_BLANK) != null
                    || processor.validation.validationAnnotation(type, component, NOT_EMPTY) != null) {
                required.add(component.getSimpleName().toString());
            }
        }
        writer.write("}");
        if (!required.isEmpty()) {
            writer.write(",\"required\":[");
            for (int index = 0; index < required.size(); index++) {
                if (index > 0) writer.write(",");
                writer.write("\"" + processor.jsonEscape(required.get(index)) + "\"");
            }
            writer.write("]");
        }
        writer.write("}");
    }

    String schemaJson(TypeMirror type, String defaultValue) {
        if (type == null) return "{}";
        String schema;
        if (type instanceof ArrayType array) {
            if (array.getComponentType().getKind() == TypeKind.BYTE) {
                schema = "{\"type\":\"string\",\"format\":\"byte\"}";
            } else {
                schema = "{\"type\":\"array\",\"items\":" + schemaJson(array.getComponentType(), "") + "}";
            }
        } else if (type instanceof DeclaredType declared) {
            String raw = processor.environment().getTypeUtils().erasure(type).toString();
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
            } else if (processor.validation.validationTypes.containsKey(raw)) {
                schema = "{\"$ref\":\"#/components/schemas/" + processor.jsonEscape(schemaName(raw)) + "\"}";
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
                : "\"" + processor.jsonEscape(defaultValue) + "\"";
        return schema.substring(0, schema.length() - 1) + ",\"default\":" + value + "}";
    }

    String enumSchema(TypeElement type) {
        StringBuilder schema = new StringBuilder("{\"type\":\"string\",\"enum\":[");
        boolean first = true;
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.ENUM_CONSTANT) continue;
            if (!first) schema.append(',');
            first = false;
            schema.append('\"').append(processor.jsonEscape(enclosed.getSimpleName().toString())).append('\"');
        }
        return schema.append("]}").toString();
    }

    String schemaName(String type) {
        return type.replace('$', '.');
    }

    String controllerBasePath(TypeElement type) {
        AnnotationMirror requestMapping = processor.annotation(type, REQUEST_MAPPING);
        if (requestMapping != null) {
            return processor.annotationString(requestMapping, "value", "");
        }
        AnnotationMirror controller = processor.annotation(type, REST_CONTROLLER);
        return controller == null ? "" : processor.annotationString(controller, "value", "");
    }

    RouteMethodModel routeMethodModel(
            ExecutableElement method,
            AnnotationMirror routeAnnotation,
            String httpMethod,
            String path) {
        if (method.getModifiers().contains(Modifier.PRIVATE)
                || method.getModifiers().contains(Modifier.STATIC)) {
            processor.environment().getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Generated routes must be non-private instance methods",
                    method);
        }
        if (method.getParameters().size() > 8) {
            processor.environment().getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Generated routes support at most 8 parameters; use a request DTO",
                    method);
        }
        List<RouteParameterModel> parameters = method.getParameters().stream()
                .map(parameter -> new RouteParameterModel(
                        parameter.asType().toString(),
                        processor.environment().getTypeUtils().erasure(parameter.asType()).toString(),
                        parameter.asType().getKind()))
                .toList();
        TypeMirror requestType = inferredRequestType(method, routeAnnotation);
        TypeMirror responseType = inferredResponseType(method, routeAnnotation);
        AnnotationMirror maxRequest = processor.annotation(method, MAX_REQUEST_BODY_SIZE);
        AnnotationMirror maxResponse = processor.annotation(method, MAX_RESPONSE_SIZE);
        boolean nativeResponse = nativeResponseType(method.getReturnType());
        RouteMethodModel model = new RouteMethodModel(
                method.getSimpleName().toString(),
                parameters,
                method.getReturnType().getKind() == TypeKind.VOID,
                generatedPrimitiveBinding(method),
                httpMethod,
                path,
                processor.environment().getTypeUtils().erasure(requestType).toString(),
                nativeResponse ? "com.reactor.rust.http.RawResponse"
                        : processor.environment().getTypeUtils().erasure(responseType).toString(),
                maxRequest == null ? 0L : processor.annotationLong(maxRequest, "value", 0L),
                maxResponse == null ? 0L : processor.annotationLong(maxResponse, "value", 0L),
                keyAdmissionModel(method),
                nativeResponse,
                processor.isCompletionStage(method.getReturnType()));
        if (processor.dispatch.borrowedBufferRoute(model)) {
            if (model.keyAdmission() != null) {
                keyAdmissionError("@KeyAdmission does not support runtime-direct borrowed ByteBuffer routes", method);
            }
            if (nativeResponse) {
                keyAdmissionError("Native response adaptation does not support runtime-direct borrowed ByteBuffer routes", method);
            }
        }
        return model;
    }

    TypeMirror inferredRequestType(
            ExecutableElement method,
            AnnotationMirror routeAnnotation) {
        TypeMirror declared = processor.annotationType(routeAnnotation, "requestType");
        if (!processor.isVoidType(declared)) {
            return declared;
        }
        for (VariableElement parameter : method.getParameters()) {
            if (processor.annotation(parameter, REQUEST_BODY) != null) {
                return parameter.asType();
            }
        }
        return processor.environment().getElementUtils().getTypeElement("java.lang.Void").asType();
    }

    TypeMirror inferredResponseType(
            ExecutableElement method,
            AnnotationMirror routeAnnotation) {
        TypeMirror declared = processor.annotationType(routeAnnotation, "responseType");
        if (!processor.isVoidType(declared)) {
            return declared;
        }
        TypeMirror inferred = unwrapResponseType(method.getReturnType());
        return inferred != null
                ? inferred
                : processor.environment().getElementUtils().getTypeElement("java.lang.Void").asType();
    }

    boolean nativeResponseType(TypeMirror type) {
        if (type instanceof javax.lang.model.type.WildcardType wildcard) {
            return wildcard.getExtendsBound() != null && nativeResponseType(wildcard.getExtendsBound());
        }
        if (!(type instanceof DeclaredType declared)) return false;
        String raw = processor.environment().getTypeUtils().erasure(type).toString();
        if (raw.equals("com.reactor.rust.dubbo.NativeResponseHandle")) return true;
        if (List.of("com.reactor.rust.http.ResponseEntity",
                "java.util.concurrent.CompletionStage", "java.util.concurrent.CompletableFuture").contains(raw)
                && !declared.getTypeArguments().isEmpty()) {
            return nativeResponseType(declared.getTypeArguments().getFirst());
        }
        return false;
    }

    KeyAdmissionModel keyAdmissionModel(ExecutableElement method) {
        AnnotationMirror admission = processor.annotation(method, "com.reactor.rust.annotations.KeyAdmission");
        if (admission == null) return null;
        String bean = processor.annotationString(admission, "bean", "");
        String key = processor.annotationString(admission, "key", "");
        if (bean.isBlank() || key.isBlank()) {
            keyAdmissionError("@KeyAdmission bean and key must not be blank", method);
            return null;
        }
        int selected = -1;
        for (int index = 0; index < method.getParameters().size(); index++) {
            VariableElement parameter = method.getParameters().get(index);
            AnnotationMirror path = processor.annotation(parameter, PATH_VARIABLE);
            AnnotationMirror query = processor.annotation(parameter, REQUEST_PARAM);
            boolean pathMatches = keyBindingMatches(path, key, parameter);
            boolean queryMatches = keyBindingMatches(query, key, parameter);
            if (!pathMatches && !queryMatches) continue;
            if (selected >= 0 || path != null && query != null) {
                keyAdmissionError("@KeyAdmission key must identify exactly one path/query binding: " + key, method);
                return null;
            }
            selected = index;
        }
        if (selected < 0) {
            keyAdmissionError("@KeyAdmission key must name a @PathVariable or @RequestParam binding: " + key, method);
            return null;
        }
        VariableElement parameter = method.getParameters().get(selected);
        for (String binding : List.of(HEADER_PARAM, COOKIE_VALUE, REQUEST_BODY)) {
            if (processor.hasAnnotation(parameter, binding)) {
                keyAdmissionError("@KeyAdmission key requires a direct path/query binding: " + key, parameter);
                return null;
            }
        }
        String type = processor.environment().getTypeUtils().erasure(parameter.asType()).toString();
        if (parameter.asType().getKind() != TypeKind.LONG && !type.equals("java.lang.String")) {
            keyAdmissionError("@KeyAdmission key must have type long or String: " + key, parameter);
            return null;
        }
        String onRejected = processor.annotationString(admission, "onRejected", "");
        if (!onRejected.isEmpty() && !validRejectionMapper(method, onRejected)) return null;
        return new KeyAdmissionModel(bean, selected, parameter.asType().getKind() == TypeKind.LONG, onRejected);
    }

    boolean validRejectionMapper(ExecutableElement route, String name) {
        List<ExecutableElement> candidates = processor.environment().getElementUtils()
                .getAllMembers((TypeElement) route.getEnclosingElement()).stream()
                .filter(element -> element.getKind() == ElementKind.METHOD
                        && element.getSimpleName().contentEquals(name))
                .map(element -> (ExecutableElement) element)
                .toList();
        if (candidates.size() != 1) {
            keyAdmissionError("@KeyAdmission onRejected must name exactly one handler method: " + name, route);
            return false;
        }
        ExecutableElement mapper = candidates.getFirst();
        if (!mapper.getModifiers().contains(Modifier.PUBLIC)
                || mapper.getModifiers().contains(Modifier.STATIC)
                || !mapper.getTypeParameters().isEmpty()
                || mapper.getParameters().size() != 1
                || !mapper.getParameters().getFirst().asType().toString().equals("java.lang.Throwable")) {
            keyAdmissionError("@KeyAdmission onRejected requires a public instance method with one Throwable parameter", route);
            return false;
        }
        boolean compatible = route.getReturnType().getKind() == TypeKind.VOID
                ? mapper.getReturnType().getKind() == TypeKind.VOID
                : mapper.getReturnType().getKind() != TypeKind.VOID
                        && processor.environment().getTypeUtils().isAssignable(mapper.getReturnType(), route.getReturnType());
        if (!compatible) {
            keyAdmissionError("@KeyAdmission onRejected return type must be assignable to the route return type", route);
        }
        return compatible;
    }

    boolean keyBindingMatches(AnnotationMirror binding, String key, VariableElement parameter) {
        if (binding == null) return false;
        String name = processor.annotationString(binding, "value", "");
        return key.equals(parameter.getSimpleName().toString()) || key.equals(name);
    }

    void keyAdmissionError(String message, Element element) {
        processor.environment().getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    PrimitiveBindingModel generatedPrimitiveBinding(ExecutableElement method) {
        if (method.getParameters().size() != 1 || processor.isCompletionStage(method.getReturnType())) {
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

        AnnotationMirror requestParam = processor.annotation(parameter, REQUEST_PARAM);
        AnnotationMirror pathVariable = processor.annotation(parameter, PATH_VARIABLE);
        if (requestParam == null && pathVariable == null) return null;
        if (requestParam != null && pathVariable != null) {
            processor.environment().getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "A generated primitive parameter cannot be both @RequestParam and @PathVariable",
                    parameter);
            return null;
        }
        if (pathVariable != null) {
            return new PrimitiveBindingModel(
                    "PATH",
                    kind,
                    processor.annotationString(pathVariable, "value", ""),
                    "",
                    "STRICT_REQUIRED");
        }

        String name = processor.annotationString(requestParam, "value", "");
        String defaultValue = processor.annotationString(requestParam, "defaultValue", "");
        boolean required = processor.annotationBoolean(requestParam, "required", true);
        if (defaultValue.isEmpty() && !required) {
            processor.environment().getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Optional primitive @RequestParam requires a defaultValue or a boxed parameter type",
                    parameter);
            return null;
        }
        if (!defaultValue.isEmpty() && !validPrimitiveDefault(kind, defaultValue)) {
            processor.environment().getMessager().printMessage(
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

    boolean validPrimitiveDefault(String kind, String value) {
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

    record KeyAdmissionModel(String bean, int parameterIndex, boolean longKey, String onRejected) {}

    record RouteMethodModel(
            String name,
            List<RouteParameterModel> parameters,
            boolean returnsVoid,
            PrimitiveBindingModel primitiveBinding,
            String httpMethod,
            String path,
            String requestType,
            String responseType,
            long maxRequestBodyBytes,
            long maxResponseBodyBytes,
            KeyAdmissionModel keyAdmission,
            boolean nativeResponse,
            boolean asyncResponse) {}

    record RouteParameterModel(String declaredType, String lookupType, TypeKind kind) {}

    record ExceptionHandlerMethodModel(
            String name,
            String parameterType,
            List<String> exceptionTypes,
            boolean returnsVoid) {}

    record PrimitiveBindingModel(
            String source,
            String kind,
            String name,
            String defaultValue,
            String mode) {}

    record OpenApiRouteModel(
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

    record OpenApiParameterModel(
            String name,
            String location,
            boolean required,
            String defaultValue,
            TypeMirror type) {}

    record OpenApiResponseModel(int status, String description, TypeMirror body) {}

}
