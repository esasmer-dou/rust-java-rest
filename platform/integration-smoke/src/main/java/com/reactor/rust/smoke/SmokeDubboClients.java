package com.reactor.rust.smoke;

import com.reactor.rust.dubbo.codegen.EnableNativeDubboClients;
import com.reactor.rust.dubbo.codegen.GenerateNativeDubboClient;

@EnableNativeDubboClients(discoveryProperty = "smoke.dubbo.discovery")
@GenerateNativeDubboClient(service = SmokeCatalogService.class)
public final class SmokeDubboClients {
    private SmokeDubboClients() {}
}
