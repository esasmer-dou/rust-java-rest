package com.reactor.rust.ffm;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FfmFastBridgeActivationTest {
    @Test void invalidCapabilitiesAndCapacityFailBeforeActivation() {
        assertThrows(IllegalArgumentException.class, () -> FfmFastBridge.tryActivate(0));
        assertThrows(IllegalArgumentException.class, () -> FfmFastBridge.tryActivate(9));
        assertThrows(IllegalArgumentException.class, () -> FfmFastBridge.tryActivate(FfmFastBridge.SLAB, 0));
        assertThrows(IllegalArgumentException.class, () -> FfmFastBridge.tryActivate(FfmFastBridge.SLAB, 33));
    }

    @Test void redisRequiresClientSpecificSelection() {
        assertThrows(IllegalArgumentException.class, () -> FfmFastBridge.tryActivate(FfmFastBridge.SLAB | FfmFastBridge.REDIS));
        assertThrows(IllegalArgumentException.class, () -> FfmFastBridge.tryActivateForRedisClient(FfmFastBridge.SLAB, 1, 16));
    }

    @Test void entryClassHasOrdinaryNonPreviewClassVersion() throws Exception {
        try (var bytes = new DataInputStream(FfmFastBridge.class.getResourceAsStream("FfmFastBridge.class"))) {
            assertEquals(0xcafebabe, bytes.readInt());
            assertEquals(0, bytes.readUnsignedShort());
            assertEquals(65, bytes.readUnsignedShort());
        }
    }

    @Test void entryCanSelectJniWithoutLoadingPreviewImplementation() throws Exception {
        fork("no-flags", List.of());
    }

    @Test void absentNativeAccessDoesNotRelyOnTheJdkWarningBehavior() throws Exception {
        fork("no-access", List.of("--enable-preview"));
    }

    @Test void absentSymbolsKeepTheLifetimeSelectionOnJni() throws Exception {
        fork("no-symbols", List.of("--enable-preview", "--enable-native-access=ALL-UNNAMED"));
    }

    static void fork(String mode, List<String> flags) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString()
                .replace("java.exe", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java"));
        command.addAll(List.of("-Xms32m", "-Xmx384m", "-XX:ActiveProcessorCount=2"));
        command.add("-verbose:class");
        command.addAll(flags);
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), Probe.class.getName(), mode));
        Path log = Files.createTempFile("ffm-preflight-" + mode, ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "FFM preflight subprocess timed out");
            String output = Files.readString(log, StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("PREACTIVATION_JNI=" + mode), output);
            assertTrue(output.contains("com.reactor.rust.ffm.FfmFastBridge"), "Missing entry class-load evidence: " + log);
            assertFalse(output.contains("java.lang.management.ManagementFactory"), "Unexpected JMX surface: " + log);
            assertFalse(output.contains("sun.management.ManagementFactoryHelper"), "Unexpected JMX surface: " + log);
            assertFalse(output.contains("javax.management.MBeanServer"), "Unexpected JMX surface: " + log);
            if (Runtime.version().feature() == 21 && System.getProperty("java.vm.name", "").contains("OpenJ9")) {
                assertFalse(output.contains("com.reactor.rust.ffm.PanamaAccess"), "Blocked VM loaded foreign adapter");
                assertFalse(output.contains("openj9.internal.foreign.abi.InternalDowncallHandler"), "Blocked VM initialized linker");
            }
            System.out.println("FFM preflight class-load evidence: " + log);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    public static final class Probe {
        public static void main(String[] args) {
            if (FfmFastBridge.tryActivate(FfmFastBridge.SLAB) != null) throw new AssertionError("Unexpected activation");
            if (FfmFastBridge.activationFailure() == null) throw new AssertionError("Missing failure reason");
            if (FfmFastBridge.tryActivate(FfmFastBridge.SLAB) != null) throw new AssertionError("JNI selection changed");
            System.out.println("PREACTIVATION_JNI=" + args[0]);
        }
    }
}
