package com.reactor.rust.bridge;

import com.reactor.rust.http.DirectJsonResponse;
import com.reactor.rust.http.JsonProducerResponse;
import com.reactor.rust.json.JsonBufferWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;

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
    }

    @AfterEach
    void clear() {
        RoutePlanRegistry.getInstance().clear();
        System.clearProperty("reactor.optimizer.mode");
        System.clearProperty("reactor.optimizer.fail-on-fallback");
        System.clearProperty("reactor.optimizer.fail-on-legacy");
        System.clearProperty("reactor.optimizer.fail-on-implicit-raw-request-data");
        System.clearProperty("reactor.optimizer.required-fast-routes");
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
}
