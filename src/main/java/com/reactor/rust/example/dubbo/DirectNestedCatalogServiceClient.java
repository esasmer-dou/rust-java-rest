package com.reactor.rust.example.dubbo;

import com.reactor.rust.dubbo.DubboMethodInvoker;
import com.reactor.rust.dubbo.sample.NestedCatalogService;

public final class DirectNestedCatalogServiceClient implements NestedCatalogService {

    private final DubboMethodInvoker<byte[]> getNestedCatalogJson;

    public DirectNestedCatalogServiceClient(DubboMethodInvoker<byte[]> getNestedCatalogJson) {
        this.getNestedCatalogJson = getNestedCatalogJson;
    }

    @Override
    public byte[] getNestedCatalogJson() {
        return getNestedCatalogJson.invoke();
    }
}
