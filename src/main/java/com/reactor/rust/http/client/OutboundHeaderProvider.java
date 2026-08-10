package com.reactor.rust.http.client;

import java.util.function.BiConsumer;

/** Optional request-context header contributor loaded once by outbound client starters. */
@FunctionalInterface
public interface OutboundHeaderProvider {
    void contribute(BiConsumer<String, String> headers);
}
