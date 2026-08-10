package com.reactor.rust.starter.openapi;

import com.reactor.rust.app.ApplicationFeature;
import com.reactor.rust.app.ApplicationFeatureContext;
import com.reactor.rust.bridge.GeneratedRouteInvoker;
import com.reactor.rust.bridge.GeneratedRouteInvokers;
import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.http.MediaType;
import com.reactor.rust.http.RawResponse;
import com.reactor.rust.openapi.OpenApiDocument;

import java.nio.charset.StandardCharsets;

public final class OpenApiFeature implements ApplicationFeature {
    private static final String UI = """
            <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Reactor API</title><style>body{font:16px sans-serif;max-width:920px;margin:48px auto;padding:0 20px;color:#17202a}pre{white-space:pre-wrap;background:#f4f6f7;padding:20px;border-radius:8px}</style></head>
            <body><h1>Reactor API</h1><p>Build-time generated OpenAPI 3.1 contract.</p><pre id="spec">Loading...</pre>
            <script>fetch('/openapi.json').then(r=>r.json()).then(v=>spec.textContent=JSON.stringify(v,null,2)).catch(e=>spec.textContent=e)</script></body></html>
            """;

    @Override
    public void configure(ApplicationFeatureContext context) {
        if (!PropertiesLoader.getBoolean("reactor.openapi.enabled", false)) return;
        RawResponse document = RawResponse.registeredJson(OpenApiDocument.bytes());
        register(OpenApiDocumentRoute.class, "document", OpenApiDocumentRoute::document);
        context.handler(new OpenApiDocumentRoute(document));

        boolean uiEnabled = PropertiesLoader.getBoolean(
                "reactor.openapi.ui.enabled",
                PropertiesLoader.getBoolean("reactor.openapi.swagger-ui.enabled", false));
        if (uiEnabled) {
            RawResponse ui = RawResponse.registeredBytes(UI.getBytes(StandardCharsets.UTF_8), MediaType.TEXT_HTML);
            register(OpenApiUiRoute.class, "ui", OpenApiUiRoute::ui);
            context.handler(new OpenApiUiRoute(ui));
        }
    }

    private static <T> void register(
            Class<T> owner,
            String method,
            java.util.function.Function<T, Object> invocation) {
        GeneratedRouteInvokers.register(
                owner,
                method,
                new Class<?>[0],
                new GeneratedRouteInvoker() {
                    @Override public int arity() { return 0; }
                    @Override public Object invoke0(Object bean) { return invocation.apply(owner.cast(bean)); }
                });
    }
}
