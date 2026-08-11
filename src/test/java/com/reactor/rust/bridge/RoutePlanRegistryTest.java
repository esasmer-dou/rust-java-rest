package com.reactor.rust.bridge;

import com.reactor.rust.http.DirectJsonResponse;
import com.reactor.rust.http.JsonProducerResponse;
import com.reactor.rust.http.RawResponse;
import com.reactor.rust.json.JsonBodyProducer;
import com.reactor.rust.json.JsonBufferWriter;
import com.reactor.rust.metrics.Metrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutePlanRegistryTest {

    static class RouteHandlers {
        public String bodyless() {
            return "ok";
        }

        public String legacy(
                ByteBuffer out,
                int offset,
                byte[] body,
                String pathParams,
                String query,
                String headers
        ) {
            return "legacy";
        }

        public int direct(
                ByteBuffer out,
                int offset,
                ByteBuffer body,
                int bodyLen,
                String pathParams,
                String query,
                String headers
        ) {
            return 0;
        }

        public DirectJsonResponse<String> directJson() {
            return DirectJsonResponse.ok(
                    "ok",
                    (value, out, offset) -> JsonBufferWriter.reusable(out, offset).string(value).result()
            );
        }

        public JsonProducerResponse producerJson() {
            return JsonProducerResponse.ok(
                    (out, offset) -> JsonBufferWriter.reusable(out, offset).string("ok").result()
            );
        }

        public JsonBodyProducer bodyProducerJson() {
            return (out, offset) -> JsonBufferWriter.reusable(out, offset).string("ok").result();
        }

        public String directQueryDto(int id) {
            return "dto-" + id;
        }

        public int directQueryPrimitiveOutput(ByteBuffer out, int offset, int id) {
            out.put(offset, (byte) '{');
            out.put(offset + 1, (byte) '}');
            return 2;
        }

        public JsonBodyProducer directQueryBodyProducerJson(int id) {
            return (out, offset) -> JsonBufferWriter.reusable(out, offset)
                    .beginObject()
                    .fieldInt("id", id)
                    .endObject()
                    .result();
        }

        public RawResponse rawJson() {
            return RawResponse.json("{\"ok\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @AfterEach
    void clear() {
        RoutePlanRegistry.getInstance().clear();
        System.clearProperty("reactor.optimizer.mode");
        System.clearProperty("reactor.optimizer.fail-on-fallback");
        System.clearProperty("reactor.optimizer.fail-on-legacy");
        System.clearProperty("reactor.optimizer.fail-on-implicit-raw-request-data");
        System.clearProperty("reactor.optimizer.fail-on-heavy-json-object-graph");
        System.clearProperty("reactor.optimizer.fail-on-benchmark-only-routes");
        System.clearProperty("reactor.optimizer.fail-on-reflection-route-metadata");
        System.clearProperty("reactor.optimizer.required-fast-routes");
        System.clearProperty("reactor.optimizer.retain-route-plans");
        Metrics.getInstance().configureCollection(true);
        GeneratedRouteInvokers.releaseStartupMetadata();
    }

    @Test
    void releasesStartupOnlyPlansWhenMetricsAreDisabled() throws Exception {
        Method method = RouteHandlers.class.getDeclaredMethod("bodyless");
        RouteDef route = new RouteDef(
                "GET", "/release", 72, Void.class.getName(), String.class.getName(),
                true, false, false, false, false, 0, 0);
        RoutePlanRegistry registry = RoutePlanRegistry.getInstance();
        registry.add(RouteExecutionPlan.from(
                route, new RouteHandlers(), method,
                false, false,
                false, false, false, false, false,
                false, false, false, false, false,
                false, false, true));
        registry.freeze();
        Metrics.getInstance().configureCollection(false);

        registry.releaseRuntimeDetailsIfConfigured();

        assertTrue(registry.plans().isEmpty());
    }

    @Test
    void routePlanJsonShowsExactBodylessStrategy() throws Exception {
        Method method = RouteHandlers.class.getDeclaredMethod("bodyless");
        RouteDef route = new RouteDef("GET", "/ok", 10, Void.class.getName(), String.class.getName(),
                true, false, false, false, false, 0, 0);
        RouteExecutionPlan plan = RouteExecutionPlan.from(route, new RouteHandlers(), method,
                false, false,
                false, false, false, false, false,
                false, false, false, false, false,
                false,
                false,
                true);

        RoutePlanRegistry registry = RoutePlanRegistry.getInstance();
        registry.add(plan);

        String json = registry.toJson();
        assertTrue(json.contains("\"strategy\":\"EXACT_BODYLESS\""));
        assertTrue(json.contains("\"optimized\":true"));
        assertTrue(json.contains("\"compiled_invoker\":true"));
        assertTrue(json.contains("\"exact_invoker\":true"));
    }

    @Test
    void routePlanJsonShowsDirectJsonResponseStrategy() throws Exception {
        Method method = RouteHandlers.class.getDeclaredMethod("directJson");
        RouteDef route = new RouteDef("GET", "/direct-json", 13, Void.class.getName(),
                DirectJsonResponse.class.getName(), true, false, false, false, false, 0, 0);
        RouteExecutionPlan plan = RouteExecutionPlan.from(route, new RouteHandlers(), method,
                false, false,
                false, false, false, false, false,
                false, false, false, false, false,
                false,
                false,
                true);

        RoutePlanRegistry registry = RoutePlanRegistry.getInstance();
        registry.add(plan);

        String json = registry.toJson();
        assertTrue(json.contains("\"strategy\":\"DIRECT_JSON_RESPONSE\""));
        assertTrue(json.contains("\"direct_json_response\":true"));
        assertTrue(json.contains("\"heavy_json_object_graph\":false"));
        assertTrue(json.contains("\"optimized\":true"));
        assertTrue(json.contains("\"compiled_invoker\":true"));
        assertTrue(json.contains("\"exact_invoker\":true"));
    }

    @Test
    void routePlanJsonShowsProducerJsonResponseStrategy() throws Exception {
        Method method = RouteHandlers.class.getDeclaredMethod("producerJson");
        RouteDef route = new RouteDef("GET", "/producer-json", 14, Void.class.getName(),
                JsonProducerResponse.class.getName(), true, false, false, false, false, 0, 0);
        RouteExecutionPlan plan = RouteExecutionPlan.from(route, new RouteHandlers(), method,
                false, false,
                false, false, false, false, false,
                false, false, false, false, false,
                false,
                false,
                true);

        RoutePlanRegistry registry = RoutePlanRegistry.getInstance();
        registry.add(plan);

        String json = registry.toJson();
        assertTrue(json.contains("\"strategy\":\"DIRECT_JSON_RESPONSE\""));
        assertTrue(json.contains("\"direct_json_response\":true"));
        assertTrue(json.contains("\"heavy_json_object_graph\":false"));
        assertTrue(json.contains("\"optimized\":true"));
    }

    @Test
    void routePlanJsonShowsBodyProducerStrategy() throws Exception {
        Method method = RouteHandlers.class.getDeclaredMethod("bodyProducerJson");
        RouteDef route = new RouteDef("GET", "/body-producer-json", 15, Void.class.getName(),
                JsonBodyProducer.class.getName(), true, false, false, false, false, 0, 0);
        RouteExecutionPlan plan = RouteExecutionPlan.from(route, new RouteHandlers(), method,
                false, false,
                false, false, false, false, false,
                false, false, false, false, false,
                false,
                false,
                true);

        RoutePlanRegistry registry = RoutePlanRegistry.getInstance();
        registry.add(plan);

        String json = registry.toJson();
        assertTrue(json.contains("\"strategy\":\"DIRECT_JSON_RESPONSE\""));
        assertTrue(json.contains("\"direct_json_response\":true"));
        assertTrue(json.contains("\"heavy_json_object_graph\":false"));
        assertTrue(json.contains("\"optimized\":true"));
    }

    @Test
    void strictModeFailsLegacyRouteAtStartup() throws Exception {
        System.setProperty("reactor.optimizer.mode", "strict");
        Method method = RouteHandlers.class.getDeclaredMethod(
                "legacy",
                ByteBuffer.class,
                int.class,
                byte[].class,
                String.class,
                String.class,
                String.class
        );
        RouteDef route = new RouteDef("GET", "/legacy", 11, byte[].class.getName(), String.class.getName(),
                false, true, true, true, false, 0, 0);

        RoutePlanRegistry registry = RoutePlanRegistry.getInstance();
        registry.add(RouteExecutionPlan.from(route, new RouteHandlers(), method,
                true, false,
                false, false, false, false, false,
                false, false, false, false, false,
                false,
                false,
                false));

        assertThrows(IllegalStateException.class, registry::validateProductionGate);
    }

    @Test
    void reflectionRouteMetadataGateRejectsCompatibilityDiscovery() throws Exception {
        System.setProperty("reactor.optimizer.fail-on-reflection-route-metadata", "true");
        Method method = RouteHandlers.class.getDeclaredMethod("bodyless");
        RouteDef route = new RouteDef(
                "GET", "/reflection", 71, Void.class.getName(), String.class.getName(),
                true, false, false, false, false, 0, 0);
        RoutePlanRegistry registry = RoutePlanRegistry.getInstance();
        registry.add(RouteExecutionPlan.from(
                route, new RouteHandlers(), method,
                false, false,
                false, false, false, false, false,
                false, false, false, false, false,
                false, false, true));

        assertThrows(IllegalStateException.class, registry::validateProductionGate);
        assertTrue(registry.toJson().contains("\"generated_route_metadata\":false"));
    }

    @Test
    void implicitRawMetadataGateFailsDirectV5WithoutRawRequestData() throws Exception {
        System.setProperty("reactor.optimizer.fail-on-implicit-raw-request-data", "true");
        Method method = RouteHandlers.class.getDeclaredMethod(
                "direct",
                ByteBuffer.class,
                int.class,
                ByteBuffer.class,
                int.class,
                String.class,
                String.class,
                String.class
        );
        RouteDef route = new RouteDef("POST", "/direct", 12, byte[].class.getName(), int.class.getName(),
                false, true, true, true, false, 0, 0);

        RoutePlanRegistry registry = RoutePlanRegistry.getInstance();
        registry.add(RouteExecutionPlan.from(route, new RouteHandlers(), method,
                false, true,
                false, false, false, false, false,
                false, false, false, false, false,
                false,
                true,
                false));

        assertThrows(IllegalStateException.class, registry::validateProductionGate);
    }

    @Test
    void heavyJsonGateFailsDirectQueryObjectGraphResponse() throws Exception {
        System.setProperty("reactor.optimizer.fail-on-heavy-json-object-graph", "true");
        Method method = RouteHandlers.class.getDeclaredMethod("directQueryDto", int.class);
        RouteDef route = new RouteDef("GET", "/heavy-dto", 16, Void.class.getName(), String.class.getName(),
                true, false, false, false, false, 0, 0);

        RoutePlanRegistry registry = RoutePlanRegistry.getInstance();
        RouteExecutionPlan plan = RouteExecutionPlan.from(route, new RouteHandlers(), method,
                false, false,
                true, false, false, false, false,
                false, false, false, false, false,
                false,
                false,
                0,
                0,
                "HEAVY_JSON",
                "heavy-json-legacy",
                false,
                false,
                true);
        registry.add(plan);

        String json = registry.toJson();
        assertTrue(plan.heavyJsonObjectGraph());
        assertTrue(json.contains("\"heavy_json_object_graph\":1"));
        assertTrue(json.contains("\"heavy_json_object_graph\":true"));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                registry::validateProductionGate
        );
        assertTrue(exception.getMessage().contains("GET /heavy-dto"));
        assertTrue(exception.getMessage().contains("object-graph"));
    }

    @Test
    void heavyJsonGateAllowsDirectQueryPrimitiveOutputWriter() throws Exception {
        System.setProperty("reactor.optimizer.fail-on-heavy-json-object-graph", "true");
        Method method = RouteHandlers.class.getDeclaredMethod(
                "directQueryPrimitiveOutput",
                ByteBuffer.class,
                int.class,
                int.class
        );
        RouteDef route = new RouteDef("GET", "/heavy-direct", 22, Void.class.getName(), String.class.getName(),
                true, false, false, false, false, 0, 0);

        RoutePlanRegistry registry = RoutePlanRegistry.getInstance();
        RouteExecutionPlan plan = RouteExecutionPlan.from(route, new RouteHandlers(), method,
                false, false,
                true, false, false, false, false,
                false, false, false, false, false,
                false,
                true,
                0,
                0,
                "HEAVY_JSON",
                "heavy-json-direct",
                false,
                false,
                true);
        registry.add(plan);

        String json = registry.toJson();
        assertFalse(plan.heavyJsonObjectGraph());
        assertTrue(json.contains("\"direct_primitive_output\":true"));
        assertTrue(json.contains("\"heavy_json_object_graph\":0"));
        registry.validateProductionGate();
    }

    @Test
    void heavyJsonGateAllowsDirectQueryBodyProducerResponse() throws Exception {
        System.setProperty("reactor.optimizer.fail-on-heavy-json-object-graph", "true");
        Method method = RouteHandlers.class.getDeclaredMethod("directQueryBodyProducerJson", int.class);
        RouteDef route = new RouteDef("GET", "/heavy-producer", 17, Void.class.getName(),
                JsonBodyProducer.class.getName(), true, false, false, false, false, 0, 0);

        RoutePlanRegistry registry = RoutePlanRegistry.getInstance();
        RouteExecutionPlan plan = RouteExecutionPlan.from(route, new RouteHandlers(), method,
                false, false,
                true, false, false, false, false,
                false, false, false, false, false,
                false,
                false,
                0,
                0,
                "HEAVY_JSON",
                "heavy-json-producer",
                false,
                false,
                true);
        registry.add(plan);

        assertFalse(plan.heavyJsonObjectGraph());
        registry.validateProductionGate();
    }

    @Test
    void heavyJsonGateAllowsRawResponse() throws Exception {
        System.setProperty("reactor.optimizer.fail-on-heavy-json-object-graph", "true");
        Method method = RouteHandlers.class.getDeclaredMethod("rawJson");
        RouteDef route = new RouteDef("GET", "/heavy-raw", 18, Void.class.getName(),
                RawResponse.class.getName(), true, false, false, false, false, 0, 0);

        RoutePlanRegistry registry = RoutePlanRegistry.getInstance();
        RouteExecutionPlan plan = RouteExecutionPlan.from(route, new RouteHandlers(), method,
                false, false,
                false, false, false, false, false,
                false, false, false, false, false,
                false,
                false,
                0,
                0,
                "HEAVY_JSON",
                "heavy-json-raw",
                false,
                false,
                true);
        registry.add(plan);

        assertFalse(plan.heavyJsonObjectGraph());
        registry.validateProductionGate();
    }

    @Test
    void benchmarkOnlyHeavyJsonObjectGraphIsSeparatedFromProductionGate() throws Exception {
        System.setProperty("reactor.optimizer.fail-on-heavy-json-object-graph", "true");
        Method method = RouteHandlers.class.getDeclaredMethod("directQueryDto", int.class);
        RouteDef route = new RouteDef("GET", "/heavy-dto-legacy", 19, Void.class.getName(), String.class.getName(),
                true, false, false, false, false, 0, 0);

        RoutePlanRegistry registry = RoutePlanRegistry.getInstance();
        RouteExecutionPlan plan = RouteExecutionPlan.from(route, new RouteHandlers(), method,
                false, false,
                true, false, false, false, false,
                false, false, false, false, false,
                false,
                false,
                0,
                0,
                "HEAVY_JSON",
                "heavy-json-legacy",
                true,
                false,
                true);
        registry.add(plan);

        String json = registry.toJson();
        assertTrue(plan.benchmarkOnly);
        assertFalse(plan.productionRoute());
        assertTrue(plan.heavyJsonObjectGraph());
        assertTrue(json.contains("\"production_routes\":0"));
        assertTrue(json.contains("\"benchmark_only\":1"));
        assertTrue(json.contains("\"production_legacy\":0"));
        assertTrue(json.contains("\"benchmark_legacy\":0"));
        assertTrue(json.contains("\"heavy_json_object_graph\":0"));
        assertTrue(json.contains("\"benchmark_heavy_json_object_graph\":1"));
        assertTrue(json.contains("\"heavy_json_object_graph\":true"));

        registry.validateProductionGate();
    }

    @Test
    void benchmarkOnlyGateCanFailBenchmarkRoutes() throws Exception {
        System.setProperty("reactor.optimizer.fail-on-benchmark-only-routes", "true");
        Method method = RouteHandlers.class.getDeclaredMethod("directQueryDto", int.class);
        RouteDef route = new RouteDef("GET", "/heavy-dto-legacy", 20, Void.class.getName(), String.class.getName(),
                true, false, false, false, false, 0, 0);

        RoutePlanRegistry registry = RoutePlanRegistry.getInstance();
        registry.add(RouteExecutionPlan.from(route, new RouteHandlers(), method,
                false, false,
                true, false, false, false, false,
                false, false, false, false, false,
                false,
                false,
                0,
                0,
                "HEAVY_JSON",
                "heavy-json-legacy",
                true,
                false,
                true));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                registry::validateProductionGate
        );
        assertTrue(exception.getMessage().contains("benchmark-only"));
    }

    @Test
    void benchmarkOnlyLegacyRouteIsSeparatedFromLegacyProductionGate() throws Exception {
        System.setProperty("reactor.optimizer.fail-on-legacy", "true");
        Method method = RouteHandlers.class.getDeclaredMethod(
                "legacy",
                ByteBuffer.class,
                int.class,
                byte[].class,
                String.class,
                String.class,
                String.class
        );
        RouteDef route = new RouteDef("GET", "/legacy-comparison", 21, byte[].class.getName(), String.class.getName(),
                false, true, true, true, false, 0, 0);

        RoutePlanRegistry registry = RoutePlanRegistry.getInstance();
        registry.add(RouteExecutionPlan.from(route, new RouteHandlers(), method,
                true, false,
                false, false, false, false, false,
                false, false, false, false, false,
                false,
                false,
                0,
                0,
                "HEAVY_JSON",
                "heavy-json-legacy",
                true,
                false,
                false));

        String json = registry.toJson();
        assertTrue(json.contains("\"production_legacy\":0"));
        assertTrue(json.contains("\"benchmark_legacy\":1"));
        registry.validateProductionGate();
    }
}
