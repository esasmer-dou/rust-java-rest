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
    private static final String RUST_PROPERTY = "com.reactor.rust.annotations.RustProperty";
    private static final String REQUEST_MAPPING = "com.reactor.rust.annotations.RequestMapping";
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
    private final Map<String, String> routes = new TreeMap<>();
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
            components.putIfAbsent(qualifiedName, new ComponentModel(
                    qualifiedName,
                    component.name().isBlank() ? beanName(type.getSimpleName().toString()) : component.name(),
                    hasAnnotation(type, PRIMARY),
                    canConstructDirectly(type)));
        }

        String basePath = annotationString(type, REQUEST_MAPPING, "value", "");
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
    }

    private void generateArtifacts() {
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
                    writer.write("        if ((prefix.isEmpty() || \"" + escape(component.type())
                            + "\".startsWith(prefix)) && !container.hasBean(" + component.type() + ".class)) {\n");
                    if (component.directConstructor()) {
                        writer.write("            container.registerGeneratedBean(" + component.type() + ".class, new "
                                + component.type() + "(), \"" + escape(component.beanName()) + "\", "
                                + component.primary() + ");\n");
                    } else {
                        writer.write("            container.registerBeanClass(" + component.type() + ".class);\n");
                    }
                    writer.write("            registered++;\n");
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
            if (annotationType.getQualifiedName().contentEquals(COMPONENT)
                    || hasAnnotation(annotationType, COMPONENT)) {
                return new ComponentAnnotation(annotationString(mirror, "value", ""));
            }
        }
        return null;
    }

    private boolean canConstructDirectly(TypeElement type) {
        if (!type.getModifiers().contains(Modifier.PUBLIC)
                || type.getModifiers().contains(Modifier.ABSTRACT)) {
            return false;
        }
        List<ExecutableElement> constructors = type.getEnclosedElements().stream()
                .filter(element -> element.getKind() == ElementKind.CONSTRUCTOR)
                .map(ExecutableElement.class::cast)
                .toList();
        if (constructors.isEmpty()) {
            return true;
        }
        return constructors.stream().anyMatch(constructor -> constructor.getParameters().isEmpty()
                && constructor.getModifiers().contains(Modifier.PUBLIC));
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

    private record ComponentModel(String type, String beanName, boolean primary, boolean directConstructor) {}
}
