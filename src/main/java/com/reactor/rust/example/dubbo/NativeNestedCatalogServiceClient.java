package com.reactor.rust.example.dubbo;

import com.reactor.rust.dubbo.NativeDubboMethodInvoker;
import com.reactor.rust.dubbo.sample.NestedCatalogService;

import java.util.concurrent.CompletableFuture;

public final class NativeNestedCatalogServiceClient implements NestedCatalogService {

    private final NativeDubboMethodInvoker<byte[]> getNestedCatalogJson;

    public NativeNestedCatalogServiceClient(NativeDubboMethodInvoker<byte[]> getNestedCatalogJson) {
        this.getNestedCatalogJson = getNestedCatalogJson;
    }

    @Override
    public byte[] getNestedCatalogJson() {
        return getNestedCatalogJson.invoke();
    }

    public CompletableFuture<byte[]> getNestedCatalogJsonAsync() {
        return getNestedCatalogJson.invokeAsync();
    }
}
