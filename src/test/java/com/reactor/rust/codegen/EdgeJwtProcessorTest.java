package com.reactor.rust.codegen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EdgeJwtProcessorTest {
    @TempDir Path directory;

    private boolean compile(String annotations) throws Exception {
        Path source = directory.resolve("AccountApi.java");
        Files.writeString(source, "import com.reactor.rust.annotations.*; @RestController(\"/account\") public class AccountApi { "
                + annotations + " @GetMapping(\"/{id}\") public byte[] get() { return null; } }");
        Path guard = directory.resolve("com/reactor/rust/security"); Files.createDirectories(guard);
        Path authenticated = guard.resolve("Authenticated.java");
        Path permitAll = guard.resolve("PermitAll.java");
        Files.writeString(authenticated, "package com.reactor.rust.security; public @interface Authenticated {}");
        Files.writeString(permitAll, "package com.reactor.rust.security; public @interface PermitAll {}");
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            var task = compiler.getTask(null, manager, ignored -> {}, List.of("-proc:only", "-d", directory.toString(), "-s", directory.toString()), null,
                    manager.getJavaFileObjects(source, authenticated, permitAll));
            task.setProcessors(List.of(new EdgeJwtProcessor()));
            return task.call();
        }
    }
    @Test void guardedRouteGeneratesExplicitStartupBindingWithoutASecretLiteral() throws Exception {
        assertTrue(compile("@RustEdgeJwt @com.reactor.rust.security.Authenticated"));
        String source = Files.readString(directory.resolve("AccountApi_EdgeJwtPolicies.java"));
        assertTrue(source.contains("registerGuardFromProperties(AccountApi.class, \"get\", new Class<?>[]{}, \"GET\", \"/account/{id}\", \"reactor.security.jwt\")"));
        assertFalse(source.contains("reflect"));
        assertFalse(source.contains("hmac-secret"));
    }
    @Test void nativeEdgeCannotReplaceJavaAuthorizationOrProtectAPublicCache() throws Exception {
        for (String invalid : List.of("@RustEdgeJwt", "@RustEdgeJwt @com.reactor.rust.security.Authenticated @com.reactor.rust.security.PermitAll",
                "@RustEdgeJwt @com.reactor.rust.security.Authenticated @RustEdgeCache(versionKey=\"v\",dataKeyPrefix=\"v:{version}:\",pathParam=\"id\")")) {
            assertFalse(compile(invalid));
        }
    }
    @Test void misalignedPolicyPrefixFailsAtCompileTime() throws Exception {
        assertFalse(compile("@RustEdgeJwt(\"other.jwt\") @com.reactor.rust.security.Authenticated"));
    }
}
