package com.reactor.rust.ffm;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Literal zero gate, separate from the full lease/copy test's bounded-allocation gate. */
@EnabledIfSystemProperty(named = "reactor.ffm.test.library", matches = ".+")
class FfmStrictZeroLeafTest {
    @Test void calibratedLeavesAllocateExactlyZeroInEveryFixedBatch() throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Xms32m", "-Xmx384m", "-XX:ActiveProcessorCount=2", "--enable-preview",
                "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
                Probe.class.getName(), System.getProperty("reactor.ffm.test.library"));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Strict zero subprocess timed out");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            System.out.print(output);
            assertEquals(0, process.exitValue(), output);
            assumeFalse(output.contains("STRICT_ZERO_UNCALIBRATED"), "Allocation gate NOT certified: " + output);
            assertTrue(output.contains("STRICT_ZERO_LEAVES=PASS"), output);
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }

    public static final class Probe {
        private static final int WARMUP = 200_000, CALLS = 100_000, BATCHES = 3;
        private static final Object[] RETAINED = new Object[100];
        private static volatile long checksum;

        public static void main(String[] args) throws Throwable {
            var platform = ManagementFactory.getThreadMXBean();
            if (!(platform instanceof ThreadMXBean bean) || !bean.isThreadAllocatedMemorySupported()) {
                System.out.println("STRICT_ZERO_UNCALIBRATED: allocation accounting unsupported");
                return;
            }
            if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
            long thread = Thread.currentThread().threadId();
            for (int i = 0; i < 1000; i++) checksum = bean.getThreadAllocatedBytes(thread);
            for (int i = 0; i < 300; i++) RETAINED[i % RETAINED.length] = new byte[32];
            long[] beforeControls = controls(bean, thread);
            System.out.println("STRICT_ZERO_FINE_CONTROLS_BEFORE=" + Arrays.toString(beforeControls));
            if (!calibrated(beforeControls)) {
                System.out.println("STRICT_ZERO_UNCALIBRATED: fine controls must each measure 4000..6400 B");
                return;
            }

            System.load(args[0]);
            FfmFastBridge bridge = FfmFastBridge.tryActivate(FfmFastBridge.SLAB | FfmFastBridge.GLOWROOT);
            if (bridge == null) throw new AssertionError("Live activation failed: " + FfmFastBridge.activationFailure());
            Linker linker = Linker.nativeLinker();
            SymbolLookup symbols = SymbolLookup.loaderLookup();
            MethodHandle version = metadata(linker, symbols, "reactor_ffm_api_version", ValueLayout.JAVA_INT);
            MethodHandle capabilities = metadata(linker, symbols, "reactor_ffm_capabilities", ValueLayout.JAVA_INT);
            MethodHandle total = metadata(linker, symbols, "reactor_slab_total_bytes", ValueLayout.JAVA_LONG);
            if ((int) version.invokeExact() != 1 || (((int) capabilities.invokeExact()) & 3) != 3
                    || (long) total.invokeExact() != 1_048_576L) throw new AssertionError("Native metadata");
            long lease = bridge.acquire();
            if (lease == 0) throw new AssertionError("No lease");
            long[][] deltas = new long[3][];
            try {
                for (int i = 0; i < 8; i++) {
                    if (bridge.writeByte(lease, i, 42) != FfmFastBridge.OK) throw new AssertionError("Write");
                }
                // Fresh process: no Glowroot configure/register/JNI startup is called.
                // Slot zero reaches native record_operation's ENGINE.get()==None path,
                // not the Java missing-capability or negative-slot shortcuts.
                if (bridge.recordOperation(0, 17, false) != FfmFastBridge.OK) throw new AssertionError("Record");
                for (int leaf = 0; leaf < deltas.length; leaf++) {
                    deltas[leaf] = measure(leaf, bean, thread, bridge, lease, version, capabilities, total);
                }
            } finally {
                if (bridge.release(lease) != FfmFastBridge.OK) throw new AssertionError("Release");
            }
            long[] afterControls = controls(bean, thread);
            String[] names = {"metadata", "slabRead", "disabledRecordOpFast"};
            System.out.println("STRICT_ZERO_PROTOCOL=warmup:200000,batches:3,callsPerBatch:100000,noRetries");
            for (int leaf = 0; leaf < names.length; leaf++) {
                System.out.println("STRICT_ZERO_" + names[leaf] + "=" + Arrays.toString(deltas[leaf]));
            }
            System.out.println("STRICT_ZERO_FINE_CONTROLS_AFTER=" + Arrays.toString(afterControls));
            if (!calibrated(afterControls)) {
                System.out.println("STRICT_ZERO_UNCALIBRATED: post-measurement controls invalid");
                return;
            }
            for (int leaf = 0; leaf < names.length; leaf++) {
                for (long delta : deltas[leaf]) {
                    if (delta != 0) throw new AssertionError(names[leaf] + " requires literal 0 B in ALL batches: "
                            + Arrays.toString(deltas[leaf]));
                }
            }
            System.out.println("STRICT_ZERO_LEAVES=PASS; disabledNativeTelemetry=true; checksum=" + checksum);
        }

        private static MethodHandle metadata(Linker linker, SymbolLookup symbols, String name, ValueLayout result) {
            var address = symbols.find(name).orElseThrow();
            var descriptor = FunctionDescriptor.of(result);
            try { return linker.downcallHandle(address, descriptor, Linker.Option.isTrivial()); }
            catch (UnsupportedOperationException unsupportedHint) { return linker.downcallHandle(address, descriptor); }
        }

        private static long[] controls(ThreadMXBean bean, long thread) {
            long[] deltas = new long[8];
            for (int batch = 0; batch < deltas.length; batch++) {
                long before = bean.getThreadAllocatedBytes(thread);
                for (int i = 0; i < RETAINED.length; i++) RETAINED[i] = new byte[32];
                deltas[batch] = bean.getThreadAllocatedBytes(thread) - before;
            }
            return deltas;
        }

        private static boolean calibrated(long[] deltas) {
            for (long delta : deltas) if (delta < 4000 || delta > 6400) return false;
            return true;
        }

        private static long[] measure(int leaf, ThreadMXBean bean, long thread, FfmFastBridge bridge, long lease,
                                      MethodHandle version, MethodHandle capabilities, MethodHandle total) throws Throwable {
            long[] deltas = new long[BATCHES];
            checksum = exercise(leaf, WARMUP, bridge, lease, version, capabilities, total);
            for (int batch = 0; batch < BATCHES; batch++) {
                long before = bean.getThreadAllocatedBytes(thread);
                long sum = exercise(leaf, CALLS, bridge, lease, version, capabilities, total);
                long after = bean.getThreadAllocatedBytes(thread);
                deltas[batch] = after - before;
                checksum = sum;
                if (before < 0 || after < before) throw new AssertionError("Invalid allocation reading");
                if (leaf == 1 && sum != 42L * CALLS) throw new AssertionError("Read result");
                if (leaf == 2 && sum != 0) throw new AssertionError("Native record status");
            }
            return deltas;
        }

        private static long exercise(int leaf, int count, FfmFastBridge bridge, long lease,
                                     MethodHandle version, MethodHandle capabilities, MethodHandle total) throws Throwable {
            long sum = 0;
            switch (leaf) {
                case 0 -> {
                    for (int i = 0; i < count; i++) {
                        sum += (int) version.invokeExact();
                        sum += (int) capabilities.invokeExact();
                        sum += (long) total.invokeExact();
                    }
                }
                case 1 -> {
                    for (int i = 0; i < count; i++) sum += bridge.readByte(lease, i & 7);
                }
                case 2 -> {
                    for (int i = 0; i < count; i++) sum += bridge.recordOperation(0, 17, false);
                }
                default -> throw new AssertionError("Unknown leaf");
            }
            return sum;
        }
    }
}
