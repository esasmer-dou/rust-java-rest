package com.reactor.rust.http.client;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Bound once at startup; the ordinary JDK request path has no native state. */
interface HttpExchangeTransport extends AutoCloseable {
    <T> CompletableFuture<HttpClientResponse<T>> execute(
            ReactorHttpClientRuntime.Request request,
            ReactorHttpClientRuntime.ResponseDecoder<T> decoder,
            boolean exposeErrors);

    default void register(URI baseUri) {}

    default void contributeHeaders(HttpRequest.Builder builder, List<OutboundHeaderProvider> providers) {
        for (OutboundHeaderProvider provider : providers) provider.contribute(builder::header);
    }

    @Override
    void close();
}

/** Capacity-managed futures propagate cancellation through generated opt-in wrappers. */
interface CancellableHttpExchange {}
