package com.reactor.rust.codegen;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.tools.Diagnostic;

/** Emits explicit startup calls, never runtime annotation scanning or per-request reflection. */
@SupportedAnnotationTypes("com.reactor.rust.annotations.RustEdgeJwt")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class EdgeJwtProcessor extends AbstractProcessor {
    private final Set<String> emitted = new HashSet<>();

    @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        for (TypeElement annotation : annotations) {
            Set<TypeElement> owners = new HashSet<>();
            for (Element element : round.getElementsAnnotatedWith(annotation)) {
                owners.add((TypeElement) element.getEnclosingElement());
            }
            for (TypeElement owner : owners) generate(owner);
        }
        return false;
    }

    private void generate(TypeElement owner) {
        String pkg = processingEnv.getElementUtils().getPackageOf(owner).getQualifiedName().toString();
        String simple = owner.getSimpleName() + "_EdgeJwtPolicies";
        String name = pkg.isEmpty() ? simple : pkg + "." + simple;
        if (!emitted.add(name)) return;
        StringBuilder calls = new StringBuilder();
        AnnotationMirror controller = find(owner, "com.reactor.rust.annotations.RequestMapping");
        if (controller == null) controller = find(owner, "com.reactor.rust.annotations.RestController");
        String base = string(controller, "value", "");
        boolean valid = true;
        for (Element element : owner.getEnclosedElements()) {
            if (!(element instanceof ExecutableElement method)) continue;
            AnnotationMirror policy = find(method, "com.reactor.rust.annotations.RustEdgeJwt");
            if (policy == null) continue;
            AnnotationMirror mapping = null;
            String verb = null;
            int count = 0;
            for (String candidate : List.of("Get", "Post", "Put", "Patch", "Delete", "Head", "Options")) {
                AnnotationMirror found = find(method, "com.reactor.rust.annotations." + candidate + "Mapping");
                if (found != null) { mapping = found; verb = candidate.toUpperCase(java.util.Locale.ROOT); count++; }
            }
            boolean guarded = find(owner, "com.reactor.rust.security.Authenticated") != null
                    || find(method, "com.reactor.rust.security.Authenticated") != null;
            if (count != 1 || !method.getModifiers().contains(Modifier.PUBLIC) || !guarded
                    || find(method, "com.reactor.rust.security.PermitAll") != null
                    || find(method, "com.reactor.rust.annotations.RustEdgeCache") != null
                    || !"reactor.security.jwt".equals(string(policy, "value", "reactor.security.jwt"))) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@RustEdgeJwt requires one public HTTP mapping, @Authenticated and the shared reactor.security.jwt policy; public cache/@PermitAll cannot be combined", method);
                valid = false; continue;
            }
            String path = join(base, string(mapping, "value", ""));
            if (path.length() > 1024 || path.chars().anyMatch(c -> c < 32 || c == 127)) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Invalid bounded edge JWT route", method);
                valid = false; continue;
            }
            calls.append("    com.reactor.rust.bridge.NativeEdgeJwt.registerGuardFromProperties(")
                    .append(owner.getQualifiedName()).append(".class, \"").append(escape(method.getSimpleName().toString()))
                    .append("\", new Class<?>[]{");
            for (int i = 0; i < method.getParameters().size(); i++) {
                if (i != 0) calls.append(", ");
                calls.append(processingEnv.getTypeUtils().erasure(method.getParameters().get(i).asType())).append(".class");
            }
            calls.append("}, \"").append(verb).append("\", \"").append(escape(path))
                    .append("\", \"reactor.security.jwt\");\n");
        }
        if (!valid) return;
        try (var out = processingEnv.getFiler().createSourceFile(name, owner).openWriter()) {
            if (!pkg.isEmpty()) out.write("package " + pkg + ";\n");
            out.write("public final class " + simple + " {\n  private " + simple + "() {}\n  public static void install() {\n" + calls + "  }\n}\n");
        } catch (IOException error) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Cannot generate edge JWT startup binding: " + error.getMessage(), owner);
        }
    }
    private AnnotationMirror find(Element element, String name) {
        for (AnnotationMirror annotation : element.getAnnotationMirrors())
            if (annotation.getAnnotationType().toString().equals(name)) return annotation;
        return null;
    }
    private String string(AnnotationMirror annotation, String key, String fallback) {
        if (annotation != null) for (var e : processingEnv.getElementUtils().getElementValuesWithDefaults(annotation).entrySet())
            if (e.getKey().getSimpleName().contentEquals(key)) return (String) e.getValue().getValue();
        return fallback;
    }
    private static String join(String base, String path) {
        if (!base.isEmpty() && !base.startsWith("/")) base = "/" + base;
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + (path.isEmpty() ? (base.isEmpty() ? "/" : "") : (path.startsWith("/") ? path : "/" + path));
    }
    private static String escape(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }
}
