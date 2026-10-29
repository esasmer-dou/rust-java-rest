package com.reactor.rust.ffm;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;

/** Separate fresh HotSpot process: abort-profile JNI slab must NOT advertise usable FFM. */
public final class FfmAbortPreflightProbe {
    public static void main(String[] args) throws Throwable {
        System.load(args[0]);
        var linker = Linker.nativeLinker();
        var symbols = SymbolLookup.loaderLookup();
        var capabilities = linker.downcallHandle(symbols.find("reactor_ffm_capabilities").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
        var allocated = linker.downcallHandle(symbols.find("reactor_slab_allocated_bytes").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG));
        if ((int) capabilities.invokeExact() != 0 || (long) allocated.invokeExact() != 0)
            throw new AssertionError("Abort profile exposed FFM or allocated slab");
        if (FfmFastBridge.tryActivate(FfmFastBridge.SLAB, 4) != null)
            throw new AssertionError("Abort profile activated FFM");
        if ((long) allocated.invokeExact() != 0) throw new AssertionError("Rejected FFM allocated slab");
        System.out.println("ABORT_FFM_DECLINED_BEFORE_SLAB_OR_IO");
    }
}
