package com.reactor.rust.http.client;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/** A real ServiceLoader provider used by the socket-level native retry tests. */
public final class NativeHeaderProvider implements OutboundHeaderProvider {
    static final AtomicInteger BUILDS = new AtomicInteger();
    static final AtomicReference<Thread> THREAD = new AtomicReference<>();

    @Override
    public void contribute(BiConsumer<String, String> headers) {
        if (!Boolean.getBoolean("test.native.header-provider")) return;
        BUILDS.incrementAndGet();
        THREAD.set(Thread.currentThread());
        headers.accept("traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01");
        for (int i = 0; i < Integer.getInteger("test.native.header-count", 0); i++) {
            headers.accept("X-Provider-" + i, "value");
        }
    }
}
