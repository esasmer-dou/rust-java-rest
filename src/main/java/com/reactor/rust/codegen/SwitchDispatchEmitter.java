package com.reactor.rust.codegen;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static com.reactor.rust.codegen.ComponentGraphScanner.ComponentModel;
import static com.reactor.rust.codegen.ComponentGraphScanner.BeanMethodModel;
import static com.reactor.rust.codegen.RouteModelCollector.RouteMethodModel;
import static com.reactor.rust.codegen.RouteModelCollector.KeyAdmissionModel;
import static com.reactor.rust.codegen.RouteModelCollector.RouteParameterModel;
import static com.reactor.rust.codegen.RouteModelCollector.ExceptionHandlerMethodModel;
import static com.reactor.rust.codegen.RouteModelCollector.PrimitiveBindingModel;
import static com.reactor.rust.codegen.ReactorStartupProcessor.*;

/** Emits switch dispatch and the legacy direct-call registration path. */
final class SwitchDispatchEmitter {

    private final ReactorStartupProcessor processor;

    SwitchDispatchEmitter(ReactorStartupProcessor processor) {
        this.processor = processor;
    }

    final Map<String, DispatchRouteModel> switchRoutes = new TreeMap<>();
    final Map<String, DispatchExceptionModel> switchExceptions = new TreeMap<>();
    boolean dispatchConfigured;
    boolean switchDispatch;
    boolean strictSwitchDispatch;
    boolean dispatchValid = true;

    void configure() {
        if (!dispatchConfigured) {
            dispatchConfigured = true;
            String mode = processor.environment().getOptions().getOrDefault("reactor.codegen.dispatch", "auto");
            strictSwitchDispatch = mode.equals("switch");
            switchDispatch = strictSwitchDispatch || mode.equals("auto");
            if (!switchDispatch && !mode.equals("legacy")) {
                dispatchValid = false;
                processor.environment().getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "reactor.codegen.dispatch must be legacy or switch or auto: " + mode);
            }
        }
    }

    void writeExceptionHandlerRegistration(Writer writer, ComponentModel component) throws IOException {
        writer.write("    public static int registerExceptionHandlers("
                + "com.reactor.rust.di.BeanContainer container, "
                + "com.reactor.rust.exception.ExceptionHandlerRegistry registry) {\n");
        if (!processor.graph.hasExceptionHandlerSurface(component)) {
            writer.write("        return 0;\n");
            writer.write("    }\n\n");
            return;
        }
        writer.write("        int registered = 0;\n");
        if (processor.routes.exceptionHandlerMethods.containsKey(component.type())) {
            writer.write("        if (container.hasBean(" + component.type() + ".class)) {\n");
            writeOwnerExceptionHandlers(writer, component.type(), "            ");
            writer.write("        }\n");
        }
        for (BeanMethodModel method : processor.graph.exceptionHandlerBeanMethods(component)) {
            writer.write("        if (container.hasBean(" + method.beanType() + ".class)) {\n");
            writeOwnerExceptionHandlers(writer, method.beanType(), "            ");
            writer.write("        }\n");
        }
        writer.write("        return registered;\n");
        writer.write("    }\n\n");
    }

    void writeOwnerExceptionHandlers(
            Writer writer,
            String ownerType,
            String indent) throws IOException {
        for (ExceptionHandlerMethodModel method :
                processor.routes.exceptionHandlerMethods.getOrDefault(ownerType, Map.of()).values()) {
            for (String exceptionType : method.exceptionTypes()) {
                DispatchExceptionModel dispatch = switchExceptions.get(exceptionDispatchKey(ownerType, method));
                if (dispatch != null) {
                    writer.write(indent + "registry.registerGenerated(container.getBean(" + ownerType
                            + ".class), " + exceptionType + ".class, new " + switchDispatchType()
                            + ".ExceptionAdapter(" + dispatch.id() + "));\n");
                    writer.write(indent + "registered++;\n");
                    continue;
                }
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

    void writeRouteInvokerRegistration(Writer writer, ComponentModel component) throws IOException {
        writer.write("    private static void registerRouteInvokers("
                + (requiresAdmissionContainer(component) ? "com.reactor.rust.di.BeanContainer container" : "")
                + ") {\n");
        writeOwnerRouteInvokers(writer, component.type());
        for (BeanMethodModel method : processor.graph.handlerBeanMethods(component)) {
            writeOwnerRouteInvokers(writer, method.beanType());
        }
        writer.write("    }\n");
    }

    void writeOwnerRouteInvokers(Writer writer, String ownerType) throws IOException {
        List<RouteMethodModel> methods = new ArrayList<>(
                processor.routes.routeMethods.getOrDefault(ownerType, Map.of()).values());
        for (RouteMethodModel method : methods) {
            KeyAdmissionModel admission = method.keyAdmission();
            if (admission != null) {
                String type = admissionType(admission);
                writer.write("        if (container.hasBean(" + ownerType + ".class)) {\n            final " + type + " keyAdmission = container.getBean("
                        + type + ".class, " + processor.javaString(admission.bean()) + ");\n");
            }
            writer.write("        com.reactor.rust.bridge.GeneratedRouteInvokers.register("
                    + ownerType + ".class, \"" + processor.escape(method.name()) + "\", new Class<?>[]{");
            for (int index = 0; index < method.parameters().size(); index++) {
                if (index > 0) writer.write(", ");
                writer.write(method.parameters().get(index).lookupType() + ".class");
            }
            DispatchRouteModel dispatch = switchRoutes.get(routeDispatchKey(ownerType, method));
            if (dispatch != null) {
                writer.write("}, new " + switchDispatchType() + ".Route" + dispatchAdapter(method)
                        + "(" + dispatch.id() + (admission == null ? "" : ", keyAdmission") + ")");
            } else {
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
            invocation = routeInvocation(ownerType, method, invocation, false);
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
                String primitiveInvocation = routeInvocation(ownerType, method,
                        "((" + ownerType + ") bean)." + method.name() + "(value)", true);
                if (method.returnsVoid()) {
                    writer.write("                " + primitiveInvocation + ";\n");
                    writer.write("                return null;\n");
                } else {
                    writer.write("                return " + primitiveInvocation + ";\n");
                }
                writer.write("            }\n");
            }
            writer.write("        }");
            }
            PrimitiveBindingModel binding = method.primitiveBinding();
            writer.write(", new com.reactor.rust.bridge.GeneratedRouteMetadata("
                    + processor.javaString(method.httpMethod()) + ", "
                    + processor.javaString(method.path()) + ", "
                    + method.requestType() + ".class, "
                    + method.responseType() + ".class, "
                    + method.maxRequestBodyBytes() + "L, "
                    + method.maxResponseBodyBytes() + "L));\n");
            if (binding != null) {
                writer.write("        com.reactor.rust.bridge.GeneratedPrimitiveBindings.register("
                        + ownerType + ".class, \"" + processor.escape(method.name()) + "\", new Class<?>[]{");
                for (int index = 0; index < method.parameters().size(); index++) {
                    if (index > 0) writer.write(", ");
                    writer.write(method.parameters().get(index).lookupType() + ".class");
                }
                writer.write("}, new com.reactor.rust.bridge.GeneratedPrimitiveBinding("
                        + "com.reactor.rust.bridge.GeneratedPrimitiveBinding.Source." + binding.source() + ", "
                        + "com.reactor.rust.bridge.GeneratedPrimitiveBinding.Kind." + binding.kind() + ", \""
                        + processor.escape(binding.name()) + "\", \"" + processor.escape(binding.defaultValue()) + "\", "
                        + "com.reactor.rust.bridge.GeneratedPrimitiveBinding.Mode." + binding.mode() + "));\n");
            }
            if (admission != null) writer.write("        }\n");
        }
    }

    // Switch mode changes only generated invocation objects, never the runtime binding pipeline.

    boolean prepareSwitchDispatch() {
        Set<String> registeredOwners = new TreeSet<>();
        for (ComponentModel component : processor.graph.components.values()) {
            registeredOwners.add(component.type());
            for (BeanMethodModel bean : processor.graph.configurationMethods.getOrDefault(component.type(), List.of())) {
                registeredOwners.add(bean.beanType());
            }
        }
        boolean valid = true;
        int[] arities = new int[9];
        int legacyRoutes = 0;
        int legacyExceptions = 0;
        for (var owner : processor.routes.routeMethods.entrySet()) {
            for (var entry : owner.getValue().entrySet()) {
                RouteMethodModel method = entry.getValue();
                ExecutableElement origin = switchMethod(owner.getKey(), entry.getKey());
                boolean supported = validateSwitchMethod(owner.getKey(), origin, registeredOwners);
                boolean runtimeDirect = borrowedBufferRoute(method) || directScalarIntRoute(origin);
                if (origin != null && !runtimeDirect) {
                    for (VariableElement parameter : origin.getParameters()) {
                        int bindings = 0;
                        for (String binding : List.of(PATH_VARIABLE, REQUEST_PARAM, HEADER_PARAM, COOKIE_VALUE, REQUEST_BODY)) {
                            if (processor.hasAnnotation(parameter, binding)) bindings++;
                        }
                        if (bindings != 1) {
                            switchDiagnostic(
                                    "Unsupported switch dispatch parameter: requires exactly one supported request binding "
                                            + "or an existing runtime-direct ABI", parameter);
                            supported = false;
                        }
                    }
                }
                int arity = method.parameters().size();
                if (arity > 8 || arities[arity] >= 256) {
                    switchDiagnostic("switch dispatch supports arity 0..8 and at most 256 routes per arity", origin);
                    supported = false;
                }
                if (origin != null && borrowedBufferRoute(method)) {
                    if (processor.isCompletionStage(origin.getReturnType())) {
                        switchDiagnostic("switch dispatch rejects async routes borrowing ByteBuffer", origin);
                        supported = false;
                    }
                }
                if (!supported) {
                    legacyRoutes++;
                    valid &= !strictSwitchDispatch;
                    continue;
                }
                if (runtimeDirect) {
                    processor.environment().getMessager().printMessage(Diagnostic.Kind.NOTE,
                            "switch dispatch retains the existing runtime direct-buffer ABI or scalar ABI for "
                                    + owner.getKey() + "#" + method.name(), origin);
                }
                arities[arity]++;
                switchRoutes.put(routeDispatchKey(owner.getKey(), method),
                        new DispatchRouteModel(switchRoutes.size(), owner.getKey(), method));
            }
        }
        for (var owner : processor.routes.exceptionHandlerMethods.entrySet()) {
            for (var entry : owner.getValue().entrySet()) {
                ExecutableElement origin = switchMethod(owner.getKey(), entry.getKey());
                boolean supported = validateSwitchMethod(owner.getKey(), origin, registeredOwners);
                if (switchExceptions.size() >= 256) {
                    switchDiagnostic("switch dispatch supports at most 256 exception handler methods", origin);
                    supported = false;
                }
                if (!supported) {
                    legacyExceptions++;
                    valid &= !strictSwitchDispatch;
                    continue;
                }
                switchExceptions.put(exceptionDispatchKey(owner.getKey(), entry.getValue()),
                        new DispatchExceptionModel(switchExceptions.size(), owner.getKey(), entry.getValue()));
            }
        }
        processor.environment().getMessager().printMessage(Diagnostic.Kind.NOTE,
                "Generated dispatch mode=" + (strictSwitchDispatch ? "switch" : "auto")
                        + ": switch routes=" + switchRoutes.size() + ", legacy routes=" + legacyRoutes
                        + ", switch exception handlers=" + switchExceptions.size()
                        + ", legacy exception handlers=" + legacyExceptions);
        return valid;
    }

    void switchDiagnostic(String message, Element origin) {
        processor.environment().getMessager().printMessage(strictSwitchDispatch ? Diagnostic.Kind.ERROR : Diagnostic.Kind.WARNING,
                message + (strictSwitchDispatch ? "; use -Areactor.codegen.dispatch=legacy"
                        : "; auto retains the legacy generated direct call (no reflection fallback)"), origin);
    }

    boolean directScalarIntRoute(ExecutableElement method) {
        return method != null && method.getParameters().size() == 1
                && method.getParameters().getFirst().asType().getKind() == TypeKind.INT
                && (processor.hasAnnotation(method, "com.reactor.rust.annotations.DirectQueryInt")
                || processor.hasAnnotation(method, "com.reactor.rust.annotations.DirectPathInt"));
    }

    boolean borrowedBufferRoute(RouteMethodModel method) {
        List<String> types = method.parameters().stream().map(RouteParameterModel::lookupType).toList();
        if (types.size() < 2 || !types.get(0).equals("java.nio.ByteBuffer") || !types.get(1).equals("int")) return false;
        return types.size() == 2
                || types.size() == 3 && List.of("int", "long", "boolean", "double", "short").contains(types.get(2))
                || types.equals(List.of("java.nio.ByteBuffer", "int", "byte[]", "java.lang.String", "java.lang.String", "java.lang.String"))
                || types.equals(List.of("java.nio.ByteBuffer", "int", "java.nio.ByteBuffer", "int", "java.lang.String", "java.lang.String", "java.lang.String"));
    }

    ExecutableElement switchMethod(String owner, String signature) {
        TypeElement type = processor.environment().getElementUtils().getTypeElement(owner);
        if (type == null) return null;
        for (Element element : type.getEnclosedElements()) {
            if (element instanceof ExecutableElement method && element.getKind() == ElementKind.METHOD
                    && processor.methodSignature(method).equals(signature)) return method;
        }
        return null;
    }

    boolean validateSwitchMethod(String owner, ExecutableElement method, Set<String> registeredOwners) {
        TypeElement type = processor.environment().getElementUtils().getTypeElement(owner);
        boolean valid = type != null && switchAccessibleType(type.asType()) && type.getTypeParameters().isEmpty()
                && type.getKind().isClass() && !type.getModifiers().contains(Modifier.ABSTRACT)
                && registeredOwners.contains(owner) && method != null
                && method.getModifiers().contains(Modifier.PUBLIC)
                && !method.getModifiers().contains(Modifier.STATIC) && method.getTypeParameters().isEmpty();
        if (valid) {
            for (VariableElement parameter : method.getParameters()) valid &= switchAccessibleType(parameter.asType());
        }
        if (!valid) {
            switchDiagnostic(
                    "Unsupported switch dispatch method in " + owner
                            + ": requires a registered component/@Bean owner, public non-generic owner/method "
                            + "and publicly accessible parameter types", method == null ? type : method);
        }
        return valid;
    }

    boolean switchAccessibleType(TypeMirror type) {
        if (type.getKind().isPrimitive()) return true;
        if (type instanceof ArrayType array) return switchAccessibleType(array.getComponentType());
        if (type instanceof javax.lang.model.type.WildcardType wildcard) {
            return (wildcard.getExtendsBound() == null || switchAccessibleType(wildcard.getExtendsBound()))
                    && (wildcard.getSuperBound() == null || switchAccessibleType(wildcard.getSuperBound()));
        }
        if (!(type instanceof DeclaredType declared)) return false;
        Element element = declared.asElement();
        for (Element enclosing = element; enclosing instanceof TypeElement; enclosing = enclosing.getEnclosingElement()) {
            if (!enclosing.getModifiers().contains(Modifier.PUBLIC)) return false;
        }
        if (processor.environment().getElementUtils().getPackageOf(element).isUnnamed()) return false;
        if (declared.getEnclosingType() instanceof DeclaredType enclosing
                && !switchAccessibleType(enclosing)) return false;
        for (TypeMirror argument : declared.getTypeArguments()) if (!switchAccessibleType(argument)) return false;
        return true;
    }

    String switchDispatchType() {
        return processor.environment().getOptions().getOrDefault("reactor.codegen.descriptorPackage", "com.reactor.generated")
                + "." + processor.environment().getOptions().getOrDefault("reactor.codegen.descriptorName", "ReactorApplicationDescriptor")
                + "__Dispatch";
    }

    String routeDispatchKey(String owner, RouteMethodModel method) {
        return owner + '#' + method.name() + '(' + method.parameters().stream()
                .map(RouteParameterModel::lookupType).collect(java.util.stream.Collectors.joining(",")) + ')';
    }

    String exceptionDispatchKey(String owner, ExceptionHandlerMethodModel method) {
        return owner + '#' + method.name() + '(' + (method.parameterType() == null ? "" : method.parameterType()) + ')';
    }

    String dispatchAdapter(RouteMethodModel method) {
        return Integer.toString(method.parameters().size())
                + (method.primitiveBinding() == null ? "" : primitiveMethod(method.primitiveBinding().kind()))
                + (method.keyAdmission() == null ? "" : method.keyAdmission().longKey() ? "KeyLong" : "KeyString");
    }

    void generateSwitchDispatch() {
        if (switchRoutes.isEmpty() && switchExceptions.isEmpty()) return;
        String type = switchDispatchType();
        int separator = type.lastIndexOf('.');
        String simple = type.substring(separator + 1);
        try (Writer writer = processor.environment().getFiler().createSourceFile(type).openWriter()) {
            writer.write("package " + type.substring(0, separator) + ";\n\npublic final class " + simple + " {\n");
            writer.write("    private " + simple + "() {}\n");
            writer.write("    private static IllegalArgumentException invalidRoute(int routeId) {\n"
                    + "        return new IllegalArgumentException(\"Invalid generated routeId: \" + routeId);\n    }\n");
            Map<String, List<DispatchRouteModel>> adapters = new TreeMap<>();
            for (DispatchRouteModel route : switchRoutes.values()) {
                adapters.computeIfAbsent(dispatchAdapter(route.method()), ignored -> new ArrayList<>()).add(route);
            }
            for (var adapter : adapters.entrySet()) {
                RouteMethodModel method = adapter.getValue().getFirst().method();
                int arity = method.parameters().size();
                PrimitiveBindingModel binding = method.primitiveBinding();
                KeyAdmissionModel admission = method.keyAdmission();
                writer.write("    public static final class Route" + adapter.getKey()
                        + " implements com.reactor.rust.bridge.GeneratedRouteInvoker {\n"
                        + "        private final int routeId;\n");
                if (admission != null) writer.write("        private final " + admissionType(admission) + " keyAdmission;\n");
                writer.write("        public Route" + adapter.getKey() + "(int routeId"
                        + (admission == null ? "" : ", " + admissionType(admission) + " keyAdmission") + ") {\n");
                writeSwitchIdValidation(writer, adapter.getValue().stream().map(DispatchRouteModel::id).toList());
                if (admission != null) writer.write("            this.keyAdmission = keyAdmission;\n");
                writer.write("            this.routeId = routeId;\n        }\n"
                        + "        @Override public int arity() { return " + arity + "; }\n"
                        + "        @Override public Object invoke" + arity + "(Object bean" + switchArguments(arity, true)
                        + ") throws Throwable {\n");
                writeSwitchRouteBody(writer, null, adapter.getValue());
                writer.write("        }\n");
                if (binding != null) {
                    String primitive = primitiveMethod(binding.kind());
                    writer.write("        @Override public Object invoke" + primitive + "(Object bean, "
                            + primitiveJavaType(binding.kind()) + " value) throws Throwable {\n");
                    writeSwitchRouteBody(writer, binding.kind(), adapter.getValue());
                    writer.write("        }\n");
                }
                writer.write("    }\n");
            }
            if (!switchExceptions.isEmpty()) {
                writer.write("    public static final class ExceptionAdapter implements com.reactor.rust.exception.GeneratedExceptionHandler {\n"
                        + "        private final int routeId;\n        public ExceptionAdapter(int routeId) {\n");
                writeSwitchIdValidation(writer, switchExceptions.values().stream().map(DispatchExceptionModel::id).toList());
                writer.write("            this.routeId = routeId;\n        }\n"
                        + "        @Override public Object invoke(Object bean, Throwable error) throws Throwable {\n"
                        + "            switch (routeId) {\n");
                for (DispatchExceptionModel handler : switchExceptions.values()) {
                    ExceptionHandlerMethodModel method = handler.method();
                    String call = "((" + handler.owner() + ") bean)." + method.name() + "("
                            + (method.parameterType() == null ? "" : "(" + method.parameterType() + ") error") + ")";
                    writeSwitchCase(writer, handler.id(), call, method.returnsVoid());
                }
                writer.write("                default: throw invalidRoute(routeId);\n            }\n        }\n    }\n");
            }
            writer.write("}\n");
        } catch (IOException failure) {
            processor.environment().getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed to generate switch dispatch: " + failure.getMessage());
        }
    }

    void writeSwitchRouteBody(Writer writer, String primitive,
                                      List<DispatchRouteModel> routes) throws IOException {
        writer.write("            switch (routeId) {\n");
        for (DispatchRouteModel route : routes) {
            RouteMethodModel method = route.method();
            String call = "((" + route.owner() + ") bean)." + method.name() + "("
                    + (primitive == null ? routeArguments(method.parameters()) : "value") + ")";
            writeSwitchCase(writer, route.id(), routeInvocation(route.owner(), method, call, primitive != null), method.returnsVoid());
        }
        writer.write("                default: throw invalidRoute(routeId);\n            }\n");
    }

    void writeSwitchCase(Writer writer, int id, String call, boolean returnsVoid) throws IOException {
        writer.write("                case " + id + ": " + (returnsVoid ? call + "; return null" : "return " + call) + ";\n");
    }

    void writeSwitchIdValidation(Writer writer, List<Integer> ids) throws IOException {
        writer.write("            switch (routeId) {\n                case " + ids.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(", ")) + " -> {}\n"
                + "                default -> throw invalidRoute(routeId);\n            }\n");
    }

    String switchArguments(int arity, boolean declarations) {
        StringBuilder arguments = new StringBuilder();
        for (int i = 0; i < arity; i++) arguments.append(declarations ? ", Object arg" : ", arg").append(i);
        return arguments.toString();
    }

    String primitiveMethod(String kind) {
        return switch (kind) {
            case "INT" -> "Int";
            case "LONG" -> "Long";
            case "BOOLEAN" -> "Boolean";
            case "DOUBLE" -> "Double";
            case "SHORT" -> "Short";
            default -> throw new IllegalArgumentException("Unsupported generated primitive kind " + kind);
        };
    }

    String primitiveJavaType(String kind) {
        return kind.toLowerCase(Locale.ROOT);
    }

    boolean requiresAdmissionContainer(ComponentModel component) {
        if (ownerRequiresAdmission(component.type())) return true;
        return processor.graph.handlerBeanMethods(component).stream()
                .anyMatch(method -> ownerRequiresAdmission(method.beanType()));
    }

    boolean ownerRequiresAdmission(String owner) {
        return processor.routes.routeMethods.getOrDefault(owner, Map.of()).values().stream()
                .anyMatch(method -> method.keyAdmission() != null);
    }

    String admissionType(KeyAdmissionModel admission) {
        return "com.reactor.rust.concurrent.LongKeyAdmission";
    }

    String routeInvocation(String owner, RouteMethodModel method, String invocation, boolean primitive) {
        KeyAdmissionModel admission = method.keyAdmission();
        if (admission != null) {
            String key = primitive ? "value" : admission.longKey()
                    ? "((Long) arg" + admission.parameterIndex() + ").longValue()"
                    : "(java.lang.String) arg" + admission.parameterIndex();
            String action = method.returnsVoid()
                    ? "() -> { " + invocation + "; return null; }" : "() -> " + invocation;
            String rejection = "";
            if (!admission.onRejected().isEmpty()) {
                String call = "((" + owner + ") bean)." + admission.onRejected() + "(error)";
                rejection = ", error -> " + (method.returnsVoid()
                        ? "{ " + call + "; return null; }" : call);
            }
            invocation = "com.reactor.rust.concurrent.KeyAdmissionSupport.invoke(keyAdmission, "
                    + key + ", " + action + rejection + ")";
        }
        if (!method.nativeResponse()) return invocation;
        return method.asyncResponse()
                ? "com.reactor.rust.dubbo.http.DubboResponses.replyAsync(" + invocation
                        + ", com.reactor.rust.dubbo.http.DubboResponses::reply)"
                : "com.reactor.rust.dubbo.http.DubboResponses.reply(" + invocation + ")";
    }

    String routeArguments(List<RouteParameterModel> parameters) {
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

    record DispatchRouteModel(int id, String owner, RouteMethodModel method) {}

    record DispatchExceptionModel(int id, String owner, ExceptionHandlerMethodModel method) {}

}
