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
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
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
    private static final String RUST_PROPERTY = "com.reactor.rust.annotations.RustProperty";
    private static final String REQUEST_MAPPING = "com.reactor.rust.annotations.RequestMapping";
    private static final String REST_CONTROLLER = "com.reactor.rust.annotations.RestController";
    private static final String RUST_ROUTE = "com.reactor.rust.annotations.RustRoute";
    private static final String DESCRIPTOR_SERVICE =
            "META-INF/services/com.reactor.rust.startup.ApplicationDescriptor";
    private static final Map<String, String> MAPPINGS = Map.of(
            "com.reactor.rust.annotations.GetMapping", "GET",
            "com.reactor.rust.annotations.PostMapping", "POST",
            "com.reactor.rust.annotations.PutMapping", "PUT",
            "com.reactor.rust.annotations.DeleteMapping", "DELETE",
            "com.reactor.rust.annotations.PatchMapping", "PATCH");

    private final Map<String, ComponentModel> components = new TreeMap<>();
    private final Set<String> deferredComponents = new TreeSet<>();
    private final Map<String, String> routes = new TreeMap<>();
    private final Set<String> routeOwners = new TreeSet<>();
    private final Map<String, Map<String, RouteMethodModel>> routeMethods = new TreeMap<>();
    private final Map<String, List<BeanMethodModel>> configurationMethods = new TreeMap<>();
    private final Set<String> deferredConfigurations = new TreeSet<>();
    private final Set<String> properties = new TreeSet<>();
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
        if (!generated && (!components.isEmpty() || !routes.isEmpty() || !properties.isEmpty())) {
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
        ComponentAnnotation component = componentAnnotation(type);
        if (component != null && type.getNestingKind().isNested() == false) {
            String qualifiedName = type.getQualifiedName().toString();
            ComponentModel model = componentModel(type, component);
            if (hasUnresolvedConstructorDependency(type)) {
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
                collectRoute(type, method, basePath);
            }
        }
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
        if (rustRoute != null) {
            httpMethod = annotationString(rustRoute, "method", "GET").toUpperCase(Locale.ROOT);
            path = annotationString(rustRoute, "path", "/");
        } else {
            for (Map.Entry<String, String> mapping : MAPPINGS.entrySet()) {
                AnnotationMirror route = annotation(method, mapping.getKey());
                if (route != null) {
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
        routeMethods.computeIfAbsent(owner.getQualifiedName().toString(), ignored -> new TreeMap<>())
                .putIfAbsent(methodSignature(method), routeMethodModel(method));
    }

    private void generateArtifacts() {
        for (ComponentModel component : components.values()) {
            generateComponentFactory(component);
        }
        writeResource("META-INF/reactor/components.idx", components.keySet());
        writeResource("META-INF/reactor/routes.idx", routes.values());
        writeResource("META-INF/reactor/properties.idx", properties);

        String packageName = processingEnv.getOptions().getOrDefault(
                "reactor.codegen.descriptorPackage", "com.reactor.generated");
        String simpleName = processingEnv.getOptions().getOrDefault(
                "reactor.codegen.descriptorName", "ReactorApplicationDescriptor");
        String qualifiedName = packageName + "." + simpleName;
        try {
            JavaFileObject source = processingEnv.getFiler().createSourceFile(qualifiedName);
            try (Writer writer = source.openWriter()) {
                writer.write("package " + packageName + ";\n\n");
                writer.write("public final class " + simpleName
                        + " implements com.reactor.rust.startup.ApplicationDescriptor {\n");
                writeListMethod(writer, "components", new ArrayList<>(components.keySet()));
                writeListMethod(writer, "routes", new ArrayList<>(routes.values()));
                writeListMethod(writer, "properties", new ArrayList<>(properties));
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
                writer.write("}\n");
            }
            writeResource(DESCRIPTOR_SERVICE, List.of(qualifiedName));
        } catch (IOException failure) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Failed to generate application descriptor: " + failure.getMessage());
        }
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
        return new DependencyModel(
                type.toString(),
                processingEnv.getTypeUtils().erasure(type).toString(),
                qualifier);
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
                    exposedTypes(method.getReturnType())));
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
                writer.write("        if (container.hasBean(" + component.type() + ".class)) return 0;\n");
                writer.write("        container.registerGeneratedFactory(" + component.type()
                        + ".class, () -> com.reactor.rust.di.GeneratedBeanFactories.create(\""
                        + escape(component.type()) + "\", () -> new " + component.type() + "("
                        + constructorArguments(component.dependencies()) + ")), \""
                        + escape(component.beanName()) + "\", " + component.primary());
                for (String exposedType : component.exposedTypes()) {
                    writer.write(", " + exposedType + ".class");
                }
                writer.write(");\n");
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
                writeConfigurationBeanRegistration(writer, component);
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

    private String constructorArguments(List<DependencyModel> dependencies) {
        List<String> arguments = new ArrayList<>(dependencies.size());
        for (DependencyModel dependency : dependencies) {
            String lookup = "container.getBean(" + dependency.lookupType() + ".class";
            if (!dependency.qualifier().isBlank()) {
                lookup += ", \"" + escape(dependency.qualifier()) + "\"";
            }
            arguments.add(lookup + ")");
        }
        return String.join(", ", arguments);
    }

    private RouteMethodModel routeMethodModel(ExecutableElement method) {
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
        return new RouteMethodModel(
                method.getSimpleName().toString(),
                parameters,
                method.getReturnType().getKind() == TypeKind.VOID);
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
        writer.write("        " + component.type() + " configuration = container.getBean("
                + component.type() + ".class);\n");
        writer.write("        int registered = 0;\n");
        for (BeanMethodModel method : methods) {
            writer.write("        if (!container.hasBean(\"" + escape(method.beanName()) + "\")) {\n");
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

    private void writeRouteInvokerRegistration(Writer writer, ComponentModel component) throws IOException {
        writer.write("    private static boolean routeInvokersRegistered;\n\n");
        writer.write("    private static synchronized void registerRouteInvokers() {\n");
        writer.write("        if (routeInvokersRegistered) return;\n");
        writeOwnerRouteInvokers(writer, component.type());
        for (BeanMethodModel method : handlerBeanMethods(component)) {
            writeOwnerRouteInvokers(writer, method.beanType());
        }
        writer.write("        routeInvokersRegistered = true;\n");
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
            writer.write("        });\n");
        }
    }

    private boolean hasHandlerSurface(ComponentModel component) {
        return routeMethods.containsKey(component.type()) || !handlerBeanMethods(component).isEmpty();
    }

    private List<BeanMethodModel> handlerBeanMethods(ComponentModel component) {
        return configurationMethods.getOrDefault(component.type(), List.of()).stream()
                .filter(method -> routeMethods.containsKey(method.beanType()))
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

    private record DependencyModel(String declaredType, String lookupType, String qualifier) {}

    private record RouteMethodModel(
            String name,
            List<RouteParameterModel> parameters,
            boolean returnsVoid) {}

    private record RouteParameterModel(String declaredType, String lookupType, TypeKind kind) {}

    private record BeanMethodModel(
            String methodName,
            String beanName,
            String beanType,
            boolean primary,
            List<DependencyModel> dependencies,
            List<String> exposedTypes) {}
}
