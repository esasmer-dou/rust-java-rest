package com.reactor.rust.ffm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "reactor.ffm.test.library", matches = ".+")
class FfmRedisPreflightTest {
    @Test void unknownClientDeclinesBeforeSlabAllocationAndNeverRunsGet() throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Xms32m", "-Xmx384m", "-XX:ActiveProcessorCount=2", "--enable-preview",
                "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
                Probe.class.getName(), System.getProperty("reactor.ffm.test.library"));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Redis preflight subprocess timed out");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("REDIS_DECLINED_WITHOUT_SLAB_OR_GET"), output);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    public static final class Probe {
        public static void main(String[] args) throws Throwable {
            System.load(args[0]);
            var allocated = Linker.nativeLinker().downcallHandle(
                    SymbolLookup.loaderLookup().find("reactor_slab_allocated_bytes").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG));
            if ((long) allocated.invokeExact() != 0) throw new AssertionError("Slab already allocated");
            if (FfmFastBridge.tryActivateForRedisClient(Integer.MAX_VALUE, 16) != null)
                throw new AssertionError("Unknown Redis client activated");
            if ((long) allocated.invokeExact() != 0) throw new AssertionError("Rejected client allocated slab");
            if (FfmFastBridge.tryActivate(FfmFastBridge.SLAB) != null)
                throw new AssertionError("Preflight JNI choice changed");
            System.out.println("REDIS_DECLINED_WITHOUT_SLAB_OR_GET");
        }
    }
}
