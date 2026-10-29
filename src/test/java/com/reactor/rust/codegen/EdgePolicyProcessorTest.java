package com.reactor.rust.codegen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EdgePolicyProcessorTest {
    @TempDir Path directory;

    private boolean compile(String annotations, String mapping) throws Exception {
        return compile(annotations, mapping, "@RequestMapping(\"/api\")");
    }
    private boolean compile(String annotations, String mapping, String typeMapping) throws Exception {
        Path source = directory.resolve("Catalog.java");
        Files.writeString(source, "import com.reactor.rust.annotations.*; " + typeMapping + " public class Catalog { "
                + annotations + " " + mapping + " public byte[] get() { return null; } }");
        Path security = Files.createDirectories(directory.resolve("com/reactor/rust/security"));
        Path authenticated = security.resolve("Authenticated.java");
        Path permitAll = security.resolve("PermitAll.java");
        Files.writeString(authenticated, "package com.reactor.rust.security; public @interface Authenticated {}");
        Files.writeString(permitAll, "package com.reactor.rust.security; public @interface PermitAll {}");
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var files = compiler.getStandardFileManager(null, null, null)) {
            var task = compiler.getTask(null, files, new DiagnosticCollector<JavaFileObject>(),
                    List.of("-d", directory.toString(), "-s", directory.toString(), "-proc:only"), null,
                    files.getJavaFileObjects(source, authenticated, permitAll));
            task.setProcessors(List.of(new EdgePolicyProcessor()));
            return task.call();
        }
    }

    @Test void generatesExplicitRegistrationAndStableManifest() throws Exception {
        assertTrue(compile("@RustEdgeCache(versionKey=\"catalog:current\", dataKeyPrefix=\"catalog:v:{version}:id:\", pathParam=\"id\", maxAgeMs=60000) @RustEdgeRateLimit(permitsPerSecond=1000, burst=200)", "@GetMapping(\"/catalog/{id}\")"));
        String generated = Files.readString(directory.resolve("Catalog_EdgePolicies.java"));
        assertTrue(generated.contains("install(int redisClientId, boolean publicEndpointConsent)"));
        assertTrue(generated.contains("\"/api/catalog/{id}\""));
        assertFalse(generated.contains("reflect"));
        String manifest = Files.readString(directory.resolve("META-INF/reactor/edge-policies.tsv"));
        assertTrue(manifest.contains("GET\t/api/catalog/{id}\tcatalog:current\tcatalog:v:{version}:id:\tid\t60000\tfalse\t1000\t200"));
    }
    @Test void rejectsPostSideEffects() throws Exception {
        assertFalse(compile("@RustEdgeCache(versionKey=\"v\",dataKeyPrefix=\"data:{version}:\",pathParam=\"id\")", "@PostMapping(\"/commands/{id}\")"));
    }
    @Test void rejectsInvalidRate() throws Exception {
        assertFalse(compile("@RustEdgeRateLimit(permitsPerSecond=0,burst=1)", "@GetMapping(\"/catalog\")"));
    }
    @Test void rejectsUnboundPathParameter() throws Exception {
        assertFalse(compile("@RustEdgeCache(versionKey=\"v\",dataKeyPrefix=\"data:{version}:\",pathParam=\"other\")", "@GetMapping(\"/catalog/{id}\")"));
    }
    @Test void rejectsTenantDimensionNotInCacheKey() throws Exception {
        assertFalse(compile("@RustEdgeCache(versionKey=\"v\",dataKeyPrefix=\"data:{version}:\",pathParam=\"id\")", "@GetMapping(\"/tenant/{tenant}/catalog/{id}\")"));
    }
    @Test void requiresVersionTemplateInsteadOfHiddenKeyConcatenation() throws Exception {
        assertFalse(compile("@RustEdgeCache(versionKey=\"v\",dataKeyPrefix=\"data:\",pathParam=\"id\")", "@GetMapping(\"/catalog/{id}\")"));
    }
    @Test void refusesImplicitPublicConsentBeforeLoadingNativeCode() {
        assertThrows(IllegalArgumentException.class, () -> com.reactor.rust.bridge.NativeEdgePolicies.register(
                "GET", "/public/{id}", 0, "current", "data:{version}:", "id", 0, false, 1, 1, false));
    }
    @Test void authenticatedCacheRouteFailsCompilation() throws Exception {
        assertFalse(compile("@com.reactor.rust.security.Authenticated @RustEdgeCache(versionKey=\"v\",dataKeyPrefix=\"data:{version}:\",pathParam=\"id\")", "@GetMapping(\"/catalog/{id}\")"));
    }
    @Test void explicitPermitAllCanOptIntoPublicSnapshot() throws Exception {
        assertTrue(compile("@com.reactor.rust.security.Authenticated @com.reactor.rust.security.PermitAll @RustEdgeCache(versionKey=\"v\",dataKeyPrefix=\"data:{version}:\",pathParam=\"id\")", "@GetMapping(\"/catalog/{id}\")"));
    }
    @Test void postCanHaveRateLimitWithoutCachingTheCommand() throws Exception {
        assertTrue(compile("@RustEdgeRateLimit(permitsPerSecond=10,burst=2)", "@PostMapping(\"/commands\")"));
        assertTrue(Files.readString(directory.resolve("META-INF/reactor/edge-policies.tsv")).contains("POST\t/api/commands"));
    }
    @Test void rejectsNonJsonMediaForNativeJsonCache() throws Exception {
        assertFalse(compile("@ContentType(\"text/plain\") @RustEdgeCache(versionKey=\"v\",dataKeyPrefix=\"data:{version}:\",pathParam=\"id\")", "@GetMapping(\"/catalog/{id}\")"));
    }
    @Test void rejectsNon200ResponseOverrideForNativeJsonCache() throws Exception {
        assertFalse(compile("@ResponseStatus(201) @RustEdgeCache(versionKey=\"v\",dataKeyPrefix=\"data:{version}:\",pathParam=\"id\")", "@GetMapping(\"/catalog/{id}\")"));
    }
    @Test void legacyDirectRouteDoesNotAccidentallyUseClassPrefix() throws Exception {
        assertTrue(compile("@RustEdgeRateLimit(permitsPerSecond=10,burst=2)", "@RustRoute(method=\"HEAD\",path=\"/direct\",requestType=Void.class,responseType=byte[].class)"));
        assertTrue(Files.readString(directory.resolve("META-INF/reactor/edge-policies.tsv")).contains("HEAD\t/direct"));
    }
    @Test void controllerPrefixAndEmptyMethodPathMatchActualRouteScanner() throws Exception {
        assertTrue(compile("@RustEdgeRateLimit(permitsPerSecond=10,burst=2)", "@GetMapping(\"\")", "@RestController(\"catalog/\")"));
        assertTrue(Files.readString(directory.resolve("META-INF/reactor/edge-policies.tsv")).contains("GET\t/catalog\t"));
    }
}
