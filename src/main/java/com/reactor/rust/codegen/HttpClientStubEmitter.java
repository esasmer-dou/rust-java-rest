package com.reactor.rust.codegen;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.DeclaredType;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static com.reactor.rust.codegen.ReactorStartupProcessor.*;

/** Collects HTTP client contracts and emits their implementation stubs. */
final class HttpClientStubEmitter {

    private final ReactorStartupProcessor processor;

    HttpClientStubEmitter(ReactorStartupProcessor processor) {
        this.processor = processor;
    }

    final Map<String, HttpClientModel> httpClients = new TreeMap<>();

    void validateHttpCircuitBreakerUsage(TypeElement type) {
        boolean annotated = processor.hasAnnotation(type, HTTP_CIRCUIT_BREAKER);
        if (annotated) httpCircuitBreaker(type);
        for (Element member : type.getEnclosedElements()) {
            if (!processor.hasAnnotation(member, HTTP_CIRCUIT_BREAKER)) continue;
            annotated = true;
            httpCircuitBreaker(member);
            if (member.getKind() != ElementKind.METHOD || !processor.hasAnnotation(member, HTTP_EXCHANGE)
                    || member.getModifiers().contains(Modifier.STATIC)
                    || member.getModifiers().contains(Modifier.PRIVATE)
                    || member.getModifiers().contains(Modifier.DEFAULT)) {
                processor.environment().getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@HttpCircuitBreaker requires an abstract @HttpExchange method", member);
            }
        }
        if (processor.hasAnnotation(type, HTTP_CLIENT)) {
            for (TypeMirror parent : type.getInterfaces()) {
                if (inheritedHttpCircuitBreaker(parent, new TreeSet<>())) annotated = true;
            }
        }
        if (annotated && (!processor.hasAnnotation(type, HTTP_CLIENT) || type.getKind() != ElementKind.INTERFACE
                || type.getNestingKind().isNested() || !type.getTypeParameters().isEmpty()
                || !type.getInterfaces().isEmpty())) {
            processor.environment().getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@HttpCircuitBreaker requires a top-level, non-generic @ReactorHttpClient interface "
                            + "without inherited methods", type);
        }
    }

    boolean inheritedHttpCircuitBreaker(TypeMirror parent, Set<String> visited) {
        if (!(processor.environment().getTypeUtils().asElement(parent) instanceof TypeElement type)
                || !visited.add(type.getQualifiedName().toString())) return false;
        if (processor.hasAnnotation(type, HTTP_CIRCUIT_BREAKER)) return true;
        for (Element member : type.getEnclosedElements()) {
            if (processor.hasAnnotation(member, HTTP_CIRCUIT_BREAKER)) return true;
        }
        for (TypeMirror ancestor : type.getInterfaces()) {
            if (inheritedHttpCircuitBreaker(ancestor, visited)) return true;
        }
        return false;
    }

    HttpCircuitBreakerModel httpCircuitBreaker(Element element) {
        AnnotationMirror config = processor.annotation(element, HTTP_CIRCUIT_BREAKER);
        if (config == null) return null;
        int window = processor.annotationInt(config, "windowSize", 64);
        int minimum = processor.annotationInt(config, "minimumCalls", 16);
        int threshold = processor.annotationInt(config, "failureRateThreshold", 50);
        long duration = processor.annotationLong(config, "openDurationMs", 10_000L);
        int probes = processor.annotationInt(config, "halfOpenMaxCalls", 2);
        if (window < 1 || window > 64 || minimum < 1 || minimum > window
                || threshold < 1 || threshold > 100 || duration < 1 || duration > Long.MAX_VALUE / 1_000_000L
                || probes < 1 || probes > 64) {
            processor.environment().getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Invalid @HttpCircuitBreaker configuration: windowSize=1..64, minimumCalls=1..windowSize, "
                            + "failureRateThreshold=1..100, openDurationMs=1.." + Long.MAX_VALUE / 1_000_000L
                            + ", halfOpenMaxCalls=1..64", element);
            return null;
        }
        return new HttpCircuitBreakerModel(window, minimum, threshold, duration, probes,
                processor.annotationBoolean(config, "record5xxAsFailure", true));
    }

    void collectHttpClient(TypeElement type) {
        if (type.getKind() != ElementKind.INTERFACE || type.getNestingKind().isNested()) {
            processor.environment().getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@ReactorHttpClient must target a top-level interface",
                    type);
            return;
        }
        AnnotationMirror client = processor.annotation(type, HTTP_CLIENT);
        String baseUrlProperty = processor.annotationString(client, "baseUrlProperty", "").trim();
        if (baseUrlProperty.isEmpty()) {
            processor.environment().getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@ReactorHttpClient baseUrlProperty must not be blank",
                    type);
            return;
        }
        ArrayList<HttpClientMethodModel> methods = new ArrayList<>();
        for (Element enclosed : type.getEnclosedElements()) {
            if (!(enclosed instanceof ExecutableElement method)
                    || enclosed.getKind() != ElementKind.METHOD) continue;
            AnnotationMirror exchange = processor.annotation(method, HTTP_EXCHANGE);
            if (exchange == null) {
                processor.environment().getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "Every abstract @ReactorHttpClient method requires @HttpExchange",
                        method);
                continue;
            }
            HttpClientMethodModel model = httpClientMethod(type, method, exchange);
            if (model != null) methods.add(model);
        }
        if (methods.isEmpty()) {
            processor.environment().getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@ReactorHttpClient must declare at least one valid @HttpExchange method",
                    type);
            return;
        }
        String packageName = processor.environment().getElementUtils().getPackageOf(type).getQualifiedName().toString();
        String simpleName = type.getSimpleName().toString();
        String generatedName = simpleName + "__ReactorHttpClient";
        String beanName = processor.annotationString(client, "name", "").trim();
        if (beanName.isEmpty()) beanName = processor.beanName(simpleName);
        httpClients.put(type.getQualifiedName().toString(), new HttpClientModel(
                type.getQualifiedName().toString(),
                packageName,
                generatedName,
                packageName.isEmpty() ? generatedName : packageName + '.' + generatedName,
                beanName,
                baseUrlProperty,
                List.copyOf(methods),
                type));
        processor.properties.properties.add(baseUrlProperty + "\tjava.lang.String\t\t" + type.getQualifiedName());
    }

    HttpClientMethodModel httpClientMethod(
            TypeElement owner,
            ExecutableElement method,
            AnnotationMirror exchange) {
        if (method.getModifiers().contains(Modifier.STATIC)
                || method.getModifiers().contains(Modifier.PRIVATE)
                || method.getModifiers().contains(Modifier.DEFAULT)
                || !method.getTypeParameters().isEmpty()) {
            processor.environment().getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Generated HTTP client methods must be non-static abstract methods without type parameters",
                    method);
            return null;
        }
        if (!(method.getReturnType() instanceof DeclaredType returnType)
                || !"java.util.concurrent.CompletionStage".equals(
                processor.environment().getTypeUtils().erasure(returnType).toString())
                || returnType.getTypeArguments().size() != 1) {
            processor.environment().getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Generated HTTP client methods must return CompletionStage<T>",
                    method);
            return null;
        }
        TypeMirror response = returnType.getTypeArguments().getFirst();
        boolean responseWrapper = false;
        if (response instanceof DeclaredType declared
                && "com.reactor.rust.http.client.HttpClientResponse".equals(
                processor.environment().getTypeUtils().erasure(declared).toString())) {
            if (declared.getTypeArguments().size() != 1) {
                processor.environment().getMessager().printMessage(
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
            if ("java.util.List".equals(processor.environment().getTypeUtils().erasure(declared).toString())
                    && declared.getTypeArguments().size() == 1
                    && declared.getTypeArguments().getFirst() instanceof DeclaredType element
                    && element.getTypeArguments().isEmpty()) {
                responseKind = "LIST";
                responseType = element.toString();
            } else {
                processor.environment().getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "Only List<Record> is supported as a parameterized outbound response body; "
                                + "use a record wrapper for other generic shapes",
                        method);
                return null;
            }
        }
        if (!(response.getKind() == TypeKind.ARRAY || response instanceof DeclaredType)) {
            processor.environment().getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Unsupported outbound response type " + response,
                    method);
            return null;
        }
        String httpMethod = String.valueOf(processor.annotationValue(exchange, "method"));
        String path = processor.annotationString(exchange, "path", "").trim();
        if (path.isEmpty()) {
            processor.environment().getMessager().printMessage(Diagnostic.Kind.ERROR, "@HttpExchange path must not be blank", method);
            return null;
        }
        long timeoutMs = processor.annotationLong(exchange, "timeoutMs", 0L);
        int retries = processor.annotationInt(exchange, "retries", -1);
        if (timeoutMs < 0L || retries < -1 || retries > 3) {
            processor.environment().getMessager().printMessage(
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
                    processor.environment().getMessager().printMessage(
                            Diagnostic.Kind.ERROR,
                            "Only one @RequestBody is supported on an outbound HTTP method",
                            parameter);
                    return null;
                }
                requestBody = true;
            }
            if (binding.kind().equals("PATH") && !path.contains("{" + binding.name() + "}")) {
                processor.environment().getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "Outbound path does not contain {" + binding.name() + "}",
                        parameter);
                return null;
            }
            parameters.add(binding);
        }
        if (requestBody && ("GET".equals(httpMethod) || "DELETE".equals(httpMethod))) {
            processor.environment().getMessager().printMessage(
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
                processor.annotationString(exchange, "contentType", "application/json; charset=utf-8"),
                processor.annotationString(exchange, "accept", "application/json"),
                timeoutMs,
                retries,
                processor.annotationBoolean(exchange, "idempotent", false),
                responseWrapper,
                List.copyOf(parameters),
                httpCircuitBreaker(processor.hasAnnotation(method, HTTP_CIRCUIT_BREAKER) ? method : owner));
    }

    HttpClientParameterModel httpClientParameter(VariableElement parameter, int index) {
        AnnotationMirror path = processor.annotation(parameter, PATH_VARIABLE);
        AnnotationMirror query = processor.annotation(parameter, REQUEST_PARAM);
        AnnotationMirror header = processor.annotation(parameter, HEADER_PARAM);
        AnnotationMirror body = processor.annotation(parameter, REQUEST_BODY);
        int count = (path == null ? 0 : 1) + (query == null ? 0 : 1)
                + (header == null ? 0 : 1) + (body == null ? 0 : 1);
        if (count != 1) {
            processor.environment().getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Each outbound HTTP parameter requires exactly one of @PathVariable, @RequestParam, "
                            + "@HeaderParam, or @RequestBody",
                    parameter);
            return null;
        }
        boolean optional = parameter.asType() instanceof DeclaredType declared
                && "java.util.Optional".equals(processor.environment().getTypeUtils().erasure(declared).toString());
        if (optional && (path != null || body != null)) {
            processor.environment().getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Optional is supported only for outbound query and header parameters",
                    parameter);
            return null;
        }
        if (path != null) return new HttpClientParameterModel(
                parameter.asType().toString(), index, "PATH", processor.annotationString(path, "value", ""), false);
        if (query != null) return new HttpClientParameterModel(
                parameter.asType().toString(), index, "QUERY", processor.annotationString(query, "value", ""), optional);
        if (header != null) return new HttpClientParameterModel(
                parameter.asType().toString(), index, "HEADER", processor.annotationString(header, "value", ""), optional);
        return new HttpClientParameterModel(parameter.asType().toString(), index, "BODY", "", false);
    }

    void generateHttpClient(HttpClientModel client) {
        try {
            JavaFileObject source = processor.environment().getFiler().createSourceFile(client.generatedType(), client.origin());
            try (Writer writer = source.openWriter()) {
                if (!client.packageName().isEmpty()) {
                    writer.write("package " + client.packageName() + ";\n\n");
                }
                writer.write("public final class " + client.generatedName() + " implements " + client.type() + " {\n");
                writer.write("    private final com.reactor.rust.http.client.ReactorHttpClientRuntime.Client client;\n\n");
                for (int index = 0; index < client.methods().size(); index++) {
                    if (client.methods().get(index).parameters().isEmpty()) {
                        writer.write("    private final com.reactor.rust.http.client.ReactorHttpClientRuntime.PreparedRequest requestPlan"
                                + index + ";\n");
                    }
                }
                for (int index = 0; index < client.methods().size(); index++) {
                    HttpCircuitBreakerModel breaker = client.methods().get(index).breaker();
                    if (breaker == null) continue;
                    writer.write("    private final com.reactor.rust.http.client.CircuitBreakerState breaker" + index
                            + " = new com.reactor.rust.http.client.CircuitBreakerState("
                            + breaker.window() + ", " + breaker.minimum() + ", " + breaker.threshold() + ", "
                            + breaker.duration() + "L, " + breaker.probes() + ", " + breaker.record5xx() + ");\n");
                }
                writer.write("    public " + client.generatedName()
                        + "(com.reactor.rust.http.client.ReactorHttpClientRuntime runtime) {\n");
                writer.write("        this.client = runtime.client(" + processor.javaString(client.baseUrlProperty()) + ");\n");
                for (int index = 0; index < client.methods().size(); index++) {
                    HttpClientMethodModel method = client.methods().get(index);
                    if (!method.parameters().isEmpty()) continue;
                    writer.write("        this.requestPlan" + index + " = client.prepare("
                            + processor.javaString(method.httpMethod()) + ", " + processor.javaString(method.path()) + ", "
                            + processor.javaString(method.contentType()) + ", " + processor.javaString(method.accept()) + ", "
                            + method.timeoutMs() + "L, " + method.retries() + ", " + method.idempotent() + ");\n");
                }
                writer.write("    }\n\n");
                int methodIndex = 0;
                for (HttpClientMethodModel method : client.methods()) {
                    int indexOfMethod = methodIndex++;
                    String breaker = "breaker" + indexOfMethod;
                    writer.write("    @Override\n");
                    writer.write("    public " + method.returnType() + " " + method.name() + "(");
                    for (int index = 0; index < method.parameters().size(); index++) {
                        if (index > 0) writer.write(", ");
                        HttpClientParameterModel parameter = method.parameters().get(index);
                        writer.write(parameter.type() + " arg" + parameter.index());
                    }
                    writer.write(") {\n");
                    if (method.breaker() != null) {
                        writer.write("        long lease = " + breaker + ".acquirePermission();\n");
                        writer.write("        try {\n");
                    }
                    if (method.parameters().isEmpty()) {
                        writer.write("        com.reactor.rust.http.client.ReactorHttpClientRuntime.Request request = requestPlan"
                                + indexOfMethod + ".request();\n");
                    } else {
                        writer.write("        com.reactor.rust.http.client.ReactorHttpClientRuntime.Request request = client.request("
                            + processor.javaString(method.httpMethod()) + ", " + processor.javaString(method.path()) + ", "
                            + processor.javaString(method.contentType()) + ", " + processor.javaString(method.accept()) + ", "
                            + method.timeoutMs() + "L, "
                            + method.retries() + ", " + method.idempotent() + ");\n");
                    }
                    for (HttpClientParameterModel parameter : method.parameters()) {
                        String argument = "arg" + parameter.index()
                                + (parameter.optional() ? ".orElse(null)" : "");
                        switch (parameter.kind()) {
                            case "PATH" -> writer.write("        request.path(" + processor.javaString(parameter.name())
                                    + ", " + argument + ");\n");
                            case "QUERY" -> writer.write("        request.query(" + processor.javaString(parameter.name())
                                    + ", " + argument + ");\n");
                            case "HEADER" -> writer.write("        request.header(" + processor.javaString(parameter.name())
                                    + ", " + argument + ");\n");
                            case "BODY" -> writer.write("        request.body(" + argument + ");\n");
                            default -> throw new IllegalStateException(
                                    "Unknown generated HTTP parameter kind " + parameter.kind());
                        }
                    }
                    String operation = method.responseKind().equals("LIST")
                            ? (method.responseWrapper() ? "executeListResponse" : "executeList")
                            : (method.responseWrapper() ? "executeResponse" : "execute");
                    if (method.breaker() == null) {
                        writer.write("        return request." + operation
                                + "(" + method.responseType() + ".class);\n");
                    } else {
                        writer.write("        return com.reactor.rust.http.client.HttpCircuitBreakerInvocation."
                                + operation + "(request, " + method.responseType() + ".class, " + breaker + ", lease);\n");
                        writer.write("        } catch (RuntimeException | Error failure) {\n");
                        writer.write("            " + breaker + ".recordFailure(lease);\n");
                        writer.write("            throw failure;\n        }\n");
                    }
                    writer.write("    }\n\n");
                }
                writer.write("}\n");
            }
        } catch (IOException failure) {
            processor.environment().getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Failed to generate HTTP client " + client.type() + ": " + failure.getMessage(),
                    client.origin());
        }
    }

    record HttpClientModel(
            String type,
            String packageName,
            String generatedName,
            String generatedType,
            String beanName,
            String baseUrlProperty,
            List<HttpClientMethodModel> methods,
            TypeElement origin) {}

    record HttpClientMethodModel(
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
            List<HttpClientParameterModel> parameters,
            HttpCircuitBreakerModel breaker) {}

    record HttpCircuitBreakerModel(int window, int minimum, int threshold,
                                           long duration, int probes, boolean record5xx) {}

    record HttpClientParameterModel(
            String type,
            int index,
            String kind,
            String name,
            boolean optional) {}

}
