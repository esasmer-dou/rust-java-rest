package com.reactor.rust.codegen;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.util.*;

/** Compile-time inventory and explicit startup registration; never scans runtime annotations. */
@SupportedAnnotationTypes({"com.reactor.rust.annotations.RustEdgeCache", "com.reactor.rust.annotations.RustEdgeRateLimit"})
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class EdgePolicyProcessor extends AbstractProcessor {
    private static final String PREFIX = "com.reactor.rust.annotations.";
    private final SortedMap<String, String> manifest = new TreeMap<>();
    private final Set<String> generated = new HashSet<>();

    @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        Set<TypeElement> owners = new LinkedHashSet<>();
        for (TypeElement annotation : annotations) {
            for (Element element : round.getElementsAnnotatedWith(annotation)) {
                if (element.getKind() == ElementKind.METHOD) owners.add((TypeElement) element.getEnclosingElement());
            }
        }
        for (TypeElement owner : owners) generate(owner);
        if (round.processingOver() && !manifest.isEmpty()) {
            try (var out = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "",
                    "META-INF/reactor/edge-policies.tsv").openWriter()) {
                out.write("# reactor-edge-v1; public GET cache; per-route HTTP rate limits; explicit install before routes\n");
                for (var entry : manifest.entrySet()) out.write(entry.getValue() + "\n");
            } catch (IOException failure) { error(null, "Cannot write edge policies: " + failure.getMessage()); }
        }
        return false;
    }

    private void generate(TypeElement owner) {
        String pkg = processingEnv.getElementUtils().getPackageOf(owner).getQualifiedName().toString();
        String name = owner.getSimpleName() + "_EdgePolicies";
        String fqcn = pkg.isEmpty() ? name : pkg + "." + name;
        if (!generated.add(fqcn)) return;
        List<String> registrations = new ArrayList<>();
        AnnotationMirror typeMapping = annotation(owner, "RequestMapping");
        String base = string(typeMapping == null ? annotation(owner, "RestController") : typeMapping, "value", "");
        boolean valid = true;
        for (Element element : owner.getEnclosedElements()) {
            if (!(element instanceof ExecutableElement method)) continue;
            AnnotationMirror cache = annotation(method, "RustEdgeCache");
            AnnotationMirror rate = annotation(method, "RustEdgeRateLimit");
            if (cache == null && rate == null) continue;
            AnnotationMirror mapping = null;
            String verb = "";
            for (String candidate : List.of("Get", "Post", "Put", "Patch", "Delete", "Head", "Options")) {
                AnnotationMirror found = annotation(method, candidate + "Mapping");
                if (found != null) {
                    if (mapping != null) { error(method, "An edge method must have exactly one HTTP mapping"); valid = false; }
                    mapping = found; verb = candidate.toUpperCase(Locale.ROOT);
                }
            }
            AnnotationMirror directRoute = annotation(method, "RustRoute");
            if (directRoute != null) {
                if (mapping != null) { error(method, "An edge method must have exactly one HTTP mapping"); valid = false; }
                mapping = directRoute;
                verb = string(directRoute, "method", "").toUpperCase(Locale.ROOT);
            }
            boolean authenticated = java.util.stream.Stream.concat(owner.getAnnotationMirrors().stream(), method.getAnnotationMirrors().stream())
                    .anyMatch(a -> a.getAnnotationType().toString().equals("com.reactor.rust.security.Authenticated"));
            boolean permitAll = method.getAnnotationMirrors().stream()
                    .anyMatch(a -> a.getAnnotationType().toString().equals("com.reactor.rust.security.PermitAll"));
            if (cache != null && authenticated && !permitAll) {
                error(method, "Native edge cache cannot bypass @Authenticated; use the Java route or explicitly public @PermitAll");
                valid = false; continue;
            }
            if (mapping == null || !method.getModifiers().contains(Modifier.PUBLIC) || cache != null && !verb.equals("GET")) {
                error(method, "Native edge policies require a public HTTP mapping; edge cache requires GET"); valid = false; continue;
            }
            if (cache != null && (number(annotation(method, "ResponseStatus"), "value", 200).intValue() != 200
                    || !string(annotation(method, "ContentType"), "value", "application/json").split(";", 2)[0].trim().equalsIgnoreCase("application/json"))) {
                error(method, "Native edge cache represents HTTP 200 application/json; status/media overrides must remain on Java routes");
                valid = false; continue;
            }
            String path = directRoute == null ? join(base, string(mapping, "value", "")) : string(directRoute, "path", "");
            String version = string(cache, "versionKey", "");
            String prefix = string(cache, "dataKeyPrefix", "");
            String param = string(cache, "pathParam", "");
            long age = number(cache, "maxAgeMs", 0).longValue();
            boolean stale = bool(cache, "serveStale");
            int permits = number(rate, "permitsPerSecond", 0).intValue();
            int burst = number(rate, "burst", 0).intValue();
            if (!List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(verb)
                    || !path.startsWith("/") || path.length() > 1024 || !safe(path) || !safe(version) || !safe(prefix) || !safe(param)
                    || age < 0 || (rate != null && (permits < 1 || permits > 1_000_000 || burst < 1 || burst > 1_000_000))) {
                error(method, "Invalid bounded native edge policy (rate/burst 1..1000000, nonnegative age, no control characters)");
                valid = false; continue;
            }
            if (cache != null && (version.isBlank() || version.length() > 256 || prefix.length() > 256
                    || param.isBlank() || param.length() > 64 || !path.contains("{" + param + "}")
                    || !prefix.contains("{version}") || prefix.indexOf("{version}") != prefix.lastIndexOf("{version}")
                    || path.replace("{" + param + "}", "").contains("{")
                    || path.indexOf("{" + param + "}") != path.lastIndexOf("{" + param + "}"))) {
                error(method, "Edge cache needs versionKey, dataKeyPrefix with exactly one {version}, and a matching {pathParam}");
                valid = false; continue;
            }
            String key = verb + "\t" + path;
            String row = key + "\t" + version + "\t" + prefix + "\t" + param + "\t" + age + "\t" + stale + "\t" + permits + "\t" + burst;
            if (manifest.putIfAbsent(key, row) != null) {
                error(method, "Duplicate native edge route: " + path); valid = false; continue;
            }
            registrations.add("    com.reactor.rust.bridge.NativeEdgePolicies.register(" + quote(verb) + ", " + quote(path)
                    + ", redisClientId, " + quote(version) + ", " + quote(prefix) + ", " + quote(param)
                    + ", " + age + "L, " + stale + ", " + permits + ", " + burst + ", publicEndpointConsent);\n");
        }
        if (!valid) return;
        try (var out = processingEnv.getFiler().createSourceFile(fqcn, owner).openWriter()) {
            if (!pkg.isEmpty()) out.write("package " + pkg + ";\n");
            out.write("public final class " + name + " {\n  private " + name + "() {}\n"
                    + "  public static void install(int redisClientId, boolean publicEndpointConsent) {\n");
            for (String registration : registrations) out.write(registration);
            out.write("  }\n}\n");
        } catch (IOException failure) { error(owner, "Cannot generate edge policies: " + failure.getMessage()); }
    }

    private AnnotationMirror annotation(Element element, String name) {
        for (AnnotationMirror value : element.getAnnotationMirrors())
            if (value.getAnnotationType().toString().equals(PREFIX + name)) return value;
        return null;
    }
    private Object value(AnnotationMirror annotation, String name, Object fallback) {
        if (annotation == null) return fallback;
        for (var entry : processingEnv.getElementUtils().getElementValuesWithDefaults(annotation).entrySet())
            if (entry.getKey().getSimpleName().contentEquals(name)) return entry.getValue().getValue();
        return fallback;
    }
    private String string(AnnotationMirror annotation, String name, String fallback) { return (String) value(annotation, name, fallback); }
    private Number number(AnnotationMirror annotation, String name, Number fallback) { return (Number) value(annotation, name, fallback); }
    private boolean bool(AnnotationMirror annotation, String name) { return (Boolean) value(annotation, name, false); }
    private static String join(String base, String path) {
        if (!base.isEmpty() && !base.startsWith("/")) base = "/" + base;
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (path.isEmpty()) return base.isEmpty() ? "/" : base;
        String result = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
                + (path.startsWith("/") ? path : "/" + path);
        return result.startsWith("/") ? result : "/" + result;
    }
    private static boolean safe(String value) { return value.chars().noneMatch(ch -> ch < 32 || ch == 127); }
    private static String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private void error(Element element, String message) { processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element); }
}
