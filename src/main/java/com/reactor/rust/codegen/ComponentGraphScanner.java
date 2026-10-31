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

import static com.reactor.rust.codegen.PropertyMetadataEmitter.ConfigurationRecordModel;
import static com.reactor.rust.codegen.HttpClientStubEmitter.HttpClientModel;
import static com.reactor.rust.codegen.ReactorStartupProcessor.*;

/** Collects the component graph and emits its factories and startup descriptor. */
final class ComponentGraphScanner {

    private final ReactorStartupProcessor processor;

    ComponentGraphScanner(ReactorStartupProcessor processor) {
        this.processor = processor;
    }

    final Map<String, ComponentModel> components = new TreeMap<>();
    final Map<String, ConditionsModel> componentConditions = new TreeMap<>();
    final Set<String> deferredComponents = new TreeSet<>();
    final Map<String, List<ScheduledMethodModel>> scheduledMethods = new TreeMap<>();
    final Map<String, List<BeanMethodModel>> configurationMethods = new TreeMap<>();
    final Set<String> deferredConfigurations = new TreeSet<>();

    void collectType(TypeElement type) {
        processor.http.validateHttpCircuitBreakerUsage(type);
        if (processor.excluded(type)) {
            return;
        }
        processor.routes.collectApplicationMetadata(type);
        processor.rejectLegacyMiddleware(type);
        if (processor.hasAnnotation(type, REQUEST) || processor.hasAnnotation(type, RESPONSE)) {
            if (type.getKind() != ElementKind.RECORD) {
                processor.environment().getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "@Request and @Response types must be records",
                        type);
            } else {
                processor.validation.validationTypes.put(type.getQualifiedName().toString(), type);
            }
        }
        if (processor.hasAnnotation(type, HTTP_CLIENT)) {
            processor.http.collectHttpClient(type);
        }
        AnnotationMirror configurationProperties = processor.annotation(type, CONFIGURATION_PROPERTIES);
        ComponentAnnotation component = configurationProperties == null
                ? componentAnnotation(type)
                : new ComponentAnnotation("");
        if (component != null && type.getNestingKind().isNested() == false) {
            String qualifiedName = type.getQualifiedName().toString();
            ComponentModel model;
            if (configurationProperties != null) {
                ConfigurationRecordModel configuration = processor.properties.configurationRecordModel(type, configurationProperties);
                processor.properties.configurationRecords.put(qualifiedName, configuration);
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

        String basePath = processor.routes.controllerBasePath(type);
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.FIELD
                    || enclosed.getKind() == ElementKind.RECORD_COMPONENT) {
                processor.properties.collectProperty(type, enclosed);
            }
            if (enclosed instanceof ExecutableElement method && enclosed.getKind() == ElementKind.METHOD) {
                processor.routes.collectExceptionHandler(type, method);
                processor.routes.collectRoute(type, method, basePath);
                collectScheduledMethod(type, method);
            }
        }
    }

    void collectScheduledMethod(TypeElement owner, ExecutableElement method) {
        AnnotationMirror scheduled = processor.annotation(method, SCHEDULED);
        if (scheduled == null) return;
        if (componentAnnotation(owner) == null) {
            processor.environment().getMessager().printMessage(
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
            processor.environment().getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@Scheduled methods must be non-private instance void methods with no parameters",
                    method);
            return;
        }
        long intervalMs = processor.annotationLong(scheduled, "intervalMs", -1L);
        String intervalProperty = processor.annotationString(scheduled, "intervalProperty", "").trim();
        if ((intervalMs > 0L) == !intervalProperty.isEmpty()) {
            processor.environment().getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@Scheduled requires exactly one of intervalMs or intervalProperty",
                    method);
            return;
        }
        long initialDelayMs = processor.annotationLong(scheduled, "initialDelayMs", 0L);
        if (initialDelayMs < 0L) {
            processor.environment().getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@Scheduled initialDelayMs must be >= 0",
                    method);
            return;
        }
        String initialDelayProperty = processor.annotationString(scheduled, "initialDelayProperty", "").trim();
        long lockAtMostMs = processor.annotationLong(scheduled, "lockAtMostMs", 60_000L);
        if (lockAtMostMs <= 0L) {
            processor.environment().getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@Scheduled lockAtMostMs must be > 0",
                    method);
            return;
        }
        String lockAtMostProperty = processor.annotationString(scheduled, "lockAtMostProperty", "").trim();
        String name = processor.annotationString(scheduled, "name", "").trim();
        if (name.isEmpty()) name = owner.getQualifiedName() + "#" + method.getSimpleName();
        String mode = String.valueOf(processor.annotationValue(scheduled, "mode"));
        ScheduledMethodModel model = new ScheduledMethodModel(
                method.getSimpleName().toString(),
                name,
                mode,
                intervalMs,
                intervalProperty,
                initialDelayMs,
                initialDelayProperty,
                processor.annotationString(scheduled, "lockName", "").trim(),
                lockAtMostMs,
                lockAtMostProperty);
        scheduledMethods.computeIfAbsent(owner.getQualifiedName().toString(), ignored -> new ArrayList<>())
                .add(model);
        if (!intervalProperty.isEmpty()) {
            processor.properties.properties.add(intervalProperty + "\tlong\t\t" + owner.getQualifiedName() + "#" + method.getSimpleName());
        }
        if (!initialDelayProperty.isEmpty()) {
            processor.properties.properties.add(initialDelayProperty + "\tlong\t" + initialDelayMs + "\t"
                    + owner.getQualifiedName() + "#" + method.getSimpleName());
        }
        if (!lockAtMostProperty.isEmpty()) {
            processor.properties.properties.add(lockAtMostProperty + "\tlong\t" + lockAtMostMs + "\t"
                    + owner.getQualifiedName() + "#" + method.getSimpleName());
        }
    }

    Set<String> allComponentTypes() {
        TreeSet<String> all = new TreeSet<>(components.keySet());
        all.addAll(processor.http.httpClients.keySet());
        return all;
    }

    List<String> descriptorPackages() {
        TreeSet<String> packages = new TreeSet<>();
        TreeSet<String> owners = new TreeSet<>(allComponentTypes());
        owners.addAll(processor.routes.routeOwners);
        for (String owner : owners) {
            TypeElement type = processor.environment().getElementUtils().getTypeElement(owner);
            if (type != null) {
                String packageName = processor.environment().getElementUtils().getPackageOf(type)
                        .getQualifiedName().toString();
                if (!packageName.isBlank()) packages.add(packageName);
            }
        }
        return List.copyOf(packages);
    }

    void writePackageCoverageMethod(Writer writer, List<String> packages) throws IOException {
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
                writer.write(processor.javaString(packageName) + ".equals(basePackage)"
                        + " || " + processor.javaString(packageName) + ".startsWith(basePackage + \".\")");
            }
            writer.write(";\n");
        }
        writer.write("    }\n\n");
    }

    ComponentAnnotation componentAnnotation(TypeElement type) {
        for (AnnotationMirror mirror : type.getAnnotationMirrors()) {
            TypeElement annotationType = (TypeElement) mirror.getAnnotationType().asElement();
            String annotationName = annotationType.getQualifiedName().toString();
            if (annotationName.equals(COMPONENT)) {
                return new ComponentAnnotation(processor.annotationString(mirror, "value", ""));
            }
            if (processor.hasAnnotation(annotationType, COMPONENT)) {
                String beanName = annotationName.equals(SERVICE) || annotationName.equals(REPOSITORY)
                        ? processor.annotationString(mirror, "value", "")
                        : "";
                return new ComponentAnnotation(beanName);
            }
        }
        return null;
    }

    ComponentModel componentModel(TypeElement type, ComponentAnnotation component) {
        if (type.getModifiers().contains(Modifier.ABSTRACT)) {
            processor.environment().getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Generated component cannot be abstract: " + type.getQualifiedName(),
                    type);
        }
        if (!type.getTypeParameters().isEmpty()) {
            processor.environment().getMessager().printMessage(
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

    ComponentModel componentModel(
            TypeElement type,
            ComponentAnnotation component,
            List<DependencyModel> dependencies) {
        String packageName = processor.environment().getElementUtils().getPackageOf(type)
                .getQualifiedName().toString();
        String factoryName = type.getSimpleName() + "__ReactorFactory";
        String factoryType = packageName.isEmpty() ? factoryName : packageName + "." + factoryName;
        return new ComponentModel(
                type.getQualifiedName().toString(),
                packageName,
                factoryName,
                factoryType,
                component.name().isBlank() ? processor.beanName(type.getSimpleName().toString()) : component.name(),
                processor.hasAnnotation(type, PRIMARY),
                dependencies,
                exposedTypes(type));
    }

    ConditionsModel conditions(Element type) {
        List<PropertyConditionModel> properties = new ArrayList<>();
        List<String> profiles = new ArrayList<>();
        for (AnnotationMirror mirror : type.getAnnotationMirrors()) {
            String name = mirror.getAnnotationType().toString();
            if (name.equals(REQUIRES_PROPERTY)) {
                properties.add(propertyCondition(mirror));
            } else if (name.equals(REQUIRES_PROPERTIES)) {
                for (AnnotationMirror nested : processor.annotationMirrors(mirror, "value")) {
                    properties.add(propertyCondition(nested));
                }
            } else if (name.equals(PROFILE)) {
                profiles.addAll(processor.annotationStrings(mirror, "value"));
            }
        }
        return new ConditionsModel(List.copyOf(properties), List.copyOf(profiles));
    }

    PropertyConditionModel propertyCondition(AnnotationMirror mirror) {
        return new PropertyConditionModel(
                processor.annotationString(mirror, "name", ""),
                processor.annotationString(mirror, "value", ""),
                processor.annotationBoolean(mirror, "matchIfMissing", false));
    }

    ExecutableElement selectConstructor(TypeElement type, List<ExecutableElement> constructors) {
        if (constructors.isEmpty()) {
            return null;
        }
        List<ExecutableElement> autowired = constructors.stream()
                .filter(constructor -> processor.hasAnnotation(
                        constructor,
                        "com.reactor.rust.di.annotation.Autowired"))
                .toList();
        ExecutableElement selected;
        if (autowired.size() == 1) {
            selected = autowired.get(0);
        } else if (autowired.size() > 1) {
            processor.environment().getMessager().printMessage(
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
                processor.environment().getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "Multiple constructors require one @Autowired constructor: " + type.getQualifiedName(),
                        type);
                return constructors.get(0);
            }
        }
        if (selected.getModifiers().contains(Modifier.PRIVATE)) {
            processor.environment().getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Generated constructor injection does not support private constructors",
                    selected);
        }
        return selected;
    }

    DependencyModel dependencyModel(VariableElement parameter) {
        TypeMirror type = parameter.asType();
        if (type.getKind().isPrimitive()) {
            processor.environment().getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Constructor dependencies must be bean reference types",
                    parameter);
        }
        String qualifier = processor.annotationString(
                parameter,
                "com.reactor.rust.di.annotation.Qualifier",
                "value",
                "");
        boolean optional = false;
        TypeMirror lookupType = type;
        if (type instanceof DeclaredType declared
                && "java.util.Optional".equals(processor.environment().getTypeUtils().erasure(type).toString())) {
            optional = true;
            if (declared.getTypeArguments().size() != 1) {
                processor.environment().getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "Optional injection requires exactly one concrete bean type",
                        parameter);
            } else {
                lookupType = declared.getTypeArguments().getFirst();
                if (lookupType.getKind() == TypeKind.WILDCARD
                        || lookupType.getKind() == TypeKind.TYPEVAR) {
                    processor.environment().getMessager().printMessage(
                            Diagnostic.Kind.ERROR,
                            "Optional injection does not support wildcard or type-variable beans",
                            parameter);
                }
            }
        }
        return new DependencyModel(
                type.toString(),
                processor.environment().getTypeUtils().erasure(lookupType).toString(),
                qualifier,
                optional);
    }

    void collectBeanMethods(TypeElement type) {
        if (!processor.hasAnnotation(type, CONFIGURATION)) {
            return;
        }
        List<BeanMethodModel> methods = new ArrayList<>();
        boolean fullyResolved = true;
        for (Element enclosed : type.getEnclosedElements()) {
            if (!(enclosed instanceof ExecutableElement method)
                    || enclosed.getKind() != ElementKind.METHOD
                    || !processor.hasAnnotation(method, BEAN)) {
                continue;
            }
            if (method.getModifiers().contains(Modifier.PRIVATE)
                    || method.getModifiers().contains(Modifier.STATIC)) {
                processor.environment().getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "Generated @Bean methods must be non-private instance methods",
                        method);
            }
            if (method.getReturnType().getKind() == TypeKind.VOID) {
                processor.environment().getMessager().printMessage(
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
            String beanName = processor.annotationString(method, BEAN, "value", "");
            if (beanName.isBlank()) {
                beanName = method.getSimpleName().toString();
            }
            TypeMirror erased = processor.environment().getTypeUtils().erasure(method.getReturnType());
            Element returnElement = processor.environment().getTypeUtils().asElement(erased);
            if (returnElement instanceof TypeElement beanType && !processor.excluded(beanType)) {
                // Explicit @Bean types may live in a dependency, outside this compilation's roots.
                // Bind their public routes at build time; do not scan the runtime classpath.
                String basePath = processor.routes.controllerBasePath(beanType);
                for (Element member : processor.environment().getElementUtils().getAllMembers(beanType)) {
                    if (member instanceof ExecutableElement route
                            && member.getKind() == ElementKind.METHOD
                            && member.getModifiers().contains(Modifier.PUBLIC)) {
                        processor.routes.collectRoute(beanType, route, basePath);
                    }
                }
            }
            methods.add(new BeanMethodModel(
                    method.getSimpleName().toString(),
                    beanName,
                    erased.toString(),
                    returnElement != null && processor.hasAnnotation(returnElement, PRIMARY),
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

    void retryDeferredConfigurations() {
        if (deferredConfigurations.isEmpty()) {
            return;
        }
        for (String typeName : List.copyOf(deferredConfigurations)) {
            TypeElement type = processor.environment().getElementUtils().getTypeElement(typeName);
            if (type != null) {
                collectBeanMethods(type);
            }
        }
    }

    void retryDeferredComponents() {
        if (deferredComponents.isEmpty()) {
            return;
        }
        for (String typeName : List.copyOf(deferredComponents)) {
            TypeElement type = processor.environment().getElementUtils().getTypeElement(typeName);
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

    boolean hasUnresolvedConstructorDependency(TypeElement type) {
        List<ExecutableElement> constructors = type.getEnclosedElements().stream()
                .filter(element -> element.getKind() == ElementKind.CONSTRUCTOR)
                .map(ExecutableElement.class::cast)
                .toList();
        ExecutableElement selected = selectConstructor(type, constructors);
        return selected != null && selected.getParameters().stream()
                .anyMatch(parameter -> parameter.asType().getKind() == TypeKind.ERROR);
    }

    List<String> exposedTypes(TypeElement type) {
        return exposedTypes(type.asType());
    }

    List<String> exposedTypes(TypeMirror type) {
        Set<String> exposed = new TreeSet<>();
        collectExposedTypes(type, exposed);
        exposed.remove(processor.environment().getTypeUtils().erasure(type).toString());
        exposed.remove(Object.class.getName());
        return List.copyOf(exposed);
    }

    void collectExposedTypes(TypeMirror type, Set<String> exposed) {
        for (TypeMirror superType : processor.environment().getTypeUtils().directSupertypes(type)) {
            String erased = processor.environment().getTypeUtils().erasure(superType).toString();
            if (!Object.class.getName().equals(erased) && exposed.add(erased)) {
                collectExposedTypes(superType, exposed);
            }
        }
    }

    void generateComponentFactory(ComponentModel component) {
        TypeElement origin = processor.environment().getElementUtils().getTypeElement(component.type());
        try {
            JavaFileObject source = processor.environment().getFiler().createSourceFile(component.factoryType(), origin);
            try (Writer writer = source.openWriter()) {
                if (!component.packageName().isEmpty()) {
                    writer.write("package " + component.packageName() + ";\n\n");
                }
                writer.write("public final class " + component.factoryName() + " {\n");
                writer.write("    private " + component.factoryName() + "() {}\n\n");
                writer.write("    public static int register(com.reactor.rust.di.BeanContainer container) {\n");
                writeConditionGuard(writer, component.type());
                writer.write("        if (container.hasBean(" + component.type() + ".class)) return 0;\n");
                ConfigurationRecordModel configuration = processor.properties.configurationRecords.get(component.type());
                String construction = configuration == null
                        ? "new " + component.type() + "(" + constructorArguments(component.dependencies()) + ")"
                        : "new " + component.type() + "(" + processor.properties.configurationArguments(configuration) + ")";
                writer.write("        container.registerGeneratedFactory(" + component.type()
                        + ".class, () -> com.reactor.rust.di.GeneratedBeanFactories.create(\""
                        + processor.escape(component.type()) + "\", () -> "
                        + generatedLifecycleFactory(origin, component.type(), construction) + "), \""
                        + processor.escape(component.beanName()) + "\", " + component.primary());
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
            writer.write("        registerRouteInvokers("
                    + (processor.dispatch.requiresAdmissionContainer(component) ? "container" : "") + ");\n");
                writer.write("        int registered = 0;\n");
                if (processor.routes.routeMethods.containsKey(component.type())) {
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
                processor.dispatch.writeExceptionHandlerRegistration(writer, component);
                writeConfigurationBeanRegistration(writer, component);
                writeScheduledTaskRegistration(writer, component);
                processor.dispatch.writeRouteInvokerRegistration(writer, component);
                writer.write("}\n");
            }
        } catch (IOException failure) {
            processor.environment().getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Failed to generate component factory for " + component.type() + ": " + failure.getMessage(),
                    origin);
        }
    }

    void writeScheduledTaskRegistration(Writer writer, ComponentModel component) throws IOException {
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
                    + processor.javaString(method.name()) + ", bean::" + method.methodName() + ", "
                    + "com.reactor.rust.annotations.Scheduled.Mode." + method.mode() + ", "
                    + method.intervalMs() + "L, " + processor.javaString(method.intervalProperty()) + ", "
                    + method.initialDelayMs() + "L, " + processor.javaString(method.initialDelayProperty()) + ", "
                    + processor.javaString(method.lockName()) + ", " + method.lockAtMostMs() + "L, "
                    + processor.javaString(method.lockAtMostProperty()) + ");\n");
        }
        writer.write("        return " + methods.size() + ";\n");
        writer.write("    }\n\n");
    }

    String constructorArguments(List<DependencyModel> dependencies) {
        List<String> arguments = new ArrayList<>(dependencies.size());
        for (DependencyModel dependency : dependencies) {
            String lookup = "container.getBean(" + dependency.lookupType() + ".class";
            if (!dependency.qualifier().isBlank()) {
                lookup += ", \"" + processor.escape(dependency.qualifier()) + "\"";
            }
            lookup += ")";
            if (dependency.optional()) {
                String present = dependency.qualifier().isBlank()
                        ? "container.hasBean(" + dependency.lookupType() + ".class)"
                        : "container.hasBean(\"" + processor.escape(dependency.qualifier()) + "\")";
                lookup = present + " ? java.util.Optional.of(" + lookup + ") : java.util.Optional.empty()";
            }
            arguments.add(lookup);
        }
        return String.join(", ", arguments);
    }

    boolean reflectionFreeGeneratedType(TypeElement type) {
        TypeMirror superType = type.getSuperclass();
        if (superType.getKind() != TypeKind.NONE
                && !Object.class.getName().equals(processor.environment().getTypeUtils().erasure(superType).toString())
                && !"java.lang.Record".equals(processor.environment().getTypeUtils().erasure(superType).toString())) {
            return false;
        }
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.FIELD
                    && (processor.hasAnnotation(enclosed, AUTOWIRED) || processor.hasAnnotation(enclosed, RUST_PROPERTY))) {
                return false;
            }
        }
        return true;
    }

    String generatedLifecycleFactory(
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

    List<ExecutableElement> lifecycleMethods(TypeElement type, String annotationType) {
        List<ExecutableElement> methods = new ArrayList<>();
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD || !processor.hasAnnotation(enclosed, annotationType)) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) enclosed;
            boolean valid = method.getParameters().isEmpty()
                    && method.getReturnType().getKind() == TypeKind.VOID
                    && !method.getModifiers().contains(Modifier.PRIVATE)
                    && !method.getModifiers().contains(Modifier.STATIC);
            if (!valid) {
                processor.environment().getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "Generated lifecycle methods must be non-private instance void methods with no parameters",
                        method);
                continue;
            }
            methods.add(method);
        }
        return methods;
    }

    void writeConditionGuard(Writer writer, String componentType) throws IOException {
        ConditionsModel conditions = componentConditions.getOrDefault(
                componentType,
                new ConditionsModel(List.of(), List.of()));
        String expression = conditionExpression(conditions);
        if (!expression.isEmpty()) {
            writer.write("        if (!(" + expression + ")) return 0;\n");
        }
    }

    String conditionExpression(ConditionsModel conditions) {
        List<String> checks = new ArrayList<>();
        for (PropertyConditionModel property : conditions.properties()) {
            checks.add("com.reactor.rust.config.ConfigurationBinder.matches("
                    + processor.javaString(property.name()) + ", " + processor.javaString(property.value()) + ", "
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

    void writeConfigurationBeanRegistration(Writer writer, ComponentModel component) throws IOException {
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
                    + "!container.hasBean(\"" + processor.escape(method.beanName()) + "\")) {\n");
            writer.write("            container.registerGeneratedFactory(" + method.beanType()
                    + ".class, () -> com.reactor.rust.di.GeneratedBeanFactories.create(\""
                    + processor.escape(component.type() + "#" + method.methodName()) + "\", () -> configuration."
                    + method.methodName() + "(" + constructorArguments(method.dependencies()) + ")), \""
                    + processor.escape(method.beanName()) + "\", " + method.primary());
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

    boolean hasHandlerSurface(ComponentModel component) {
        return processor.routes.routeMethods.containsKey(component.type()) || !handlerBeanMethods(component).isEmpty();
    }

    boolean hasExceptionHandlerSurface(ComponentModel component) {
        return processor.routes.exceptionHandlerMethods.containsKey(component.type())
                || !exceptionHandlerBeanMethods(component).isEmpty();
    }

    List<BeanMethodModel> handlerBeanMethods(ComponentModel component) {
        return configurationMethods.getOrDefault(component.type(), List.of()).stream()
                .filter(method -> processor.routes.routeMethods.containsKey(method.beanType()))
                .toList();
    }

    List<BeanMethodModel> exceptionHandlerBeanMethods(ComponentModel component) {
        return configurationMethods.getOrDefault(component.type(), List.of()).stream()
                .filter(method -> processor.routes.exceptionHandlerMethods.containsKey(method.beanType()))
                .toList();
    }

    record ComponentAnnotation(String name) {}

    record ComponentModel(
            String type,
            String packageName,
            String factoryName,
            String factoryType,
            String beanName,
            boolean primary,
            List<DependencyModel> dependencies,
            List<String> exposedTypes) {}

    record ConditionsModel(
            List<PropertyConditionModel> properties,
            List<String> profiles) {}

    record PropertyConditionModel(
            String name,
            String value,
            boolean matchIfMissing) {}

    record DependencyModel(
            String declaredType,
            String lookupType,
            String qualifier,
            boolean optional) {}

    record ScheduledMethodModel(
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

    record BeanMethodModel(
            String methodName,
            String beanName,
            String beanType,
            boolean primary,
            List<DependencyModel> dependencies,
            List<String> exposedTypes,
            ConditionsModel conditions) {}

    void generateDescriptor(Map<String, String> generatedValidators) {
        String packageName = processor.environment().getOptions().getOrDefault(
                "reactor.codegen.descriptorPackage", "com.reactor.generated");
        boolean validationOnly = components.isEmpty()
                && processor.routes.routes.isEmpty()
                && processor.properties.properties.isEmpty()
                && !generatedValidators.isEmpty();
        String defaultDescriptorName = validationOnly
                ? "ReactorValidationDescriptor_" + processor.stableSuffix(generatedValidators.keySet())
                : "ReactorApplicationDescriptor";
        String simpleName = processor.environment().getOptions().getOrDefault(
                "reactor.codegen.descriptorName", defaultDescriptorName);
        String qualifiedName = packageName + "." + simpleName;
        try {
            JavaFileObject source = processor.environment().getFiler().createSourceFile(qualifiedName);
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
                        writer.write("            case \"" + processor.escape(entry.getKey()) + "\" -> "
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
                    writer.write("        if (prefix.isEmpty() || \"" + processor.escape(component.type())
                            + "\".startsWith(prefix)) {\n");
                    writer.write("            registered += " + component.factoryType() + ".register(container);\n");
                    writer.write("        }\n");
                }
                for (HttpClientModel client : processor.http.httpClients.values()) {
                    String match = "(prefix.isEmpty() || \"" + processor.escape(client.type()) + "\".startsWith(prefix))";
                    String enabled = conditionExpression(conditions(client.origin()));
                    if (!enabled.isEmpty()) match += " && (" + enabled + ")";
                    writer.write("        if (" + match + ") {\n");
                    writer.write("            if (!container.hasBean(" + client.type() + ".class)) {\n");
                    writer.write("                container.registerGeneratedFactory(" + client.type()
                            + ".class, () -> new " + client.generatedType()
                            + "(container.getBean(com.reactor.rust.http.client.ReactorHttpClientRuntime.class)), \""
                            + processor.escape(client.beanName()) + "\", false);\n");
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
                    writer.write("        if (prefix.isEmpty() || \"" + processor.escape(component.type())
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
                    writer.write("        if (prefix.isEmpty() || \"" + processor.escape(component.type())
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
                    writer.write("        if (prefix.isEmpty() || \"" + processor.escape(component.type())
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
                    writer.write("        if (prefix.isEmpty() || \"" + processor.escape(component.type())
                            + "\".startsWith(prefix)) {\n");
                    writer.write("            registered += " + component.factoryType()
                            + ".registerScheduledTasks(container);\n");
                    writer.write("        }\n");
                }
                writer.write("        return registered;\n");
                writer.write("    }\n");
                writer.write("}\n");
            }
            processor.writeResource(DESCRIPTOR_SERVICE, List.of(qualifiedName));
        } catch (IOException failure) {
            processor.environment().getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Failed to generate application descriptor: " + failure.getMessage());
        }
    }

}
