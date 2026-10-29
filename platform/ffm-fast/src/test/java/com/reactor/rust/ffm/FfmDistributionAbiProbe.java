package com.reactor.rust.ffm;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;

/** Checks the inspected constant-only JNI ABI leaves in the actual distribution binary. */
public final class FfmDistributionAbiProbe {
    public static void main(String[] args) throws Throwable {
        System.load(System.getProperty("reactor.ffm.test.library"));
        SymbolLookup symbols = SymbolLookup.loaderLookup();
        Linker linker = Linker.nativeLinker();
        String[] names = {
                "Java_com_reactor_rust_bridge_NativeBridge_nativeAbiVersion",
                "Java_com_reactor_rust_dubbo_NativeDubboBridge_nativeDubboAbiVersion",
                "Java_com_reactor_rust_cache_internal_nativebridge_NativeRedisBridge_nativeAbiVersion",
                "Java_com_reactor_glowroot_agent_runtime_NativeTelemetry_nativeGlowrootAbiVersion"
        };
        int[] expected = {29, 7, 6, 6};
        for (int i = 0; i < names.length; i++) {
            var handle = linker.downcallHandle(symbols.find(names[i]).orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            // These four inspected functions return constants and NEVER access
            // JNIEnv/jclass. Null is NOT valid for arbitrary JNI entry points.
            int actual = (int) handle.invokeExact(MemorySegment.NULL, MemorySegment.NULL);
            if (actual != expected[i]) throw new AssertionError(names[i] + "=" + actual);
        }
        String[] exports = {"reactor_ffm_api_version", "reactor_ffm_capabilities",
                "reactor_glowroot_record_op_fast", "reactor_redis_client_status", "reactor_redis_get_into_slot", "reactor_redis_acquire_get",
                "reactor_slab_acquire", "reactor_slab_allocated_bytes", "reactor_slab_base_ptr",
                "reactor_slab_configure", "reactor_slab_copy_in", "reactor_slab_copy_out",
                "reactor_slab_layout_bytes", "reactor_slab_read_byte", "reactor_slab_release",
                "reactor_slab_total_bytes", "reactor_slab_write_byte"};
        for (String name : exports) symbols.find(name).orElseThrow();
        System.out.println("DISTRIBUTION_ABIS=29/7/6/6; FFM_C_EXPORTS=" + exports.length);
    }
}
