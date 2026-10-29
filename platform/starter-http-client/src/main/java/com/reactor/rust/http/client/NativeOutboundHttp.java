package com.reactor.rust.http.client;

import com.reactor.rust.bridge.NativeLibraryLoader;

/** Additive API, independent of the existing REST ABI 29. Never invokes Java from Tokio. */
final class NativeOutboundHttp {
    private NativeOutboundHttp() {}

    static void loadAndProbe() {
        try {
            NativeLibraryLoader.load();
            if (version() != 1) throw new IllegalStateException("Unsupported outbound HTTP native API version");
            // Handle zero is a documented no-op, resolving every symbol before first use.
            register(0, "");
            send(0, 0, "", "", new String[0], new byte[0], 1, 0);
            poll(0, 0);
            pollBody(0, new Object[1], 0);
            cancel(0, 0);
            stop(0);
            destroy(0);
        } catch (UnsatisfiedLinkError missing) {
            throw new IllegalStateException("rust-native outbound HTTP requires a native library built with "
                    + "--features outbound-http (API 1 with split completion). Set rust.lib.path to the matching artifact. No JDK fallback.", missing);
        }
    }

    static native int version();
    static native long create(int threads, int maxInflight, int maxConnections, int maxOrigins,
                              int maxIdlePerOrigin, int maxRequestBytes, int maxResponseBytes,
                              int maxHeaders, int maxHeaderBytes, long byteBudget,
                              long connectTimeoutMs, long idleTimeoutMs);
    static native void register(long handle, String origin);
    static native void send(long handle, long id, String method, String uri, String[] headers,
                            byte[] body, long timeoutMs, long delayMs);
    static native byte[] poll(long handle, long timeoutMs);
    static native byte[] pollBody(long handle, Object[] metadata, long timeoutMs);
    static native boolean cancel(long handle, long id);
    static native void stop(long handle);
    static native void destroy(long handle);
}
