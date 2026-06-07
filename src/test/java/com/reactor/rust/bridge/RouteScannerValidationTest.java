package com.reactor.rust.bridge;

import com.reactor.rust.annotations.BenchmarkOnlyRoute;
import com.reactor.rust.annotations.DirectPathInt;
import com.reactor.rust.annotations.DirectQueryInt;
import com.reactor.rust.annotations.GetMapping;
import com.reactor.rust.annotations.JniQueueAdmission;
import com.reactor.rust.annotations.NativeStaticRoute;
import com.reactor.rust.annotations.RouteAdmission;
import com.reactor.rust.annotations.RouteWorkload;
import com.reactor.rust.http.JsonProducerResponse;
import com.reactor.rust.http.RawResponse;
import com.reactor.rust.json.JsonBodyProducer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteScannerValidationTest {

    @AfterEach
    void clearRoutePlans() {
        RoutePlanRegistry.getInstance().clear();
    }

    static class InvalidDirectPathHandler {
        @GetMapping(value = "/items/{id}", responseType = String.class)
        @DirectPathInt("missing")
        public int get(ByteBuffer out, int offset, int id) {
            return 0;
        }
    }

    static class InvalidNativeStaticPathHandler {
        @GetMapping(value = "/static/{id}", responseType = RawResponse.class)
        @NativeStaticRoute
        public RawResponse get() {
            return null;
        }
    }

    static class InvalidNativeStaticReturnHandler {
        @GetMapping(value = "/static", responseType = String.class)
        @NativeStaticRoute
        public String get() {
            return "not-static";
        }
    }

    static class ValidDirectQueryProducerHandler {
        @GetMapping(value = "/producer", responseType = JsonProducerResponse.class)
        @DirectQueryInt(value = "items", defaultValue = 100, min = 1, max = 1000)
        public JsonProducerResponse get(int items) {
            return JsonProducerResponse.ok((out, offset) -> 0);
        }
    }

    static class ValidDirectQueryBodyProducerHandler {
        @GetMapping(value = "/producer", responseType = JsonBodyProducer.class)
        @DirectQueryInt(value = "items", defaultValue = 100, min = 1, max = 1000)
        public JsonBodyProducer get(int items) {
            return (out, offset) -> 0;
        }
    }

    static class ValidDirectQueryRawResponseHandler {
        @GetMapping(value = "/raw", responseType = RawResponse.class)
        @DirectQueryInt(value = "items", defaultValue = 100, min = 1, max = 1000)
        public RawResponse get(int items) {
            return RawResponse.json(("{\"items\":" + items + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    static class ValidDirectQueryPrimitiveOutputHandler {
        @GetMapping(value = "/heavy", responseType = String.class)
        @RouteWorkload(value = RouteWorkload.Type.HEAVY_JSON, budget = "heavy-json-direct")
        @DirectQueryInt(value = "items", defaultValue = 100, min = 1, max = 1000)
        public int get(ByteBuffer out, int offset, int items) {
            out.put(offset, (byte) '{');
            out.put(offset + 1, (byte) '}');
            return 2;
        }
    }

    static class ValidDirectPathRawResponseHandler {
        @GetMapping(value = "/raw/{items}", responseType = RawResponse.class)
        @DirectPathInt("items")
        public RawResponse get(int items) {
            return RawResponse.json(("{\"items\":" + items + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    static class ValidDirectQueryAsyncBodyProducerHandler {
        @GetMapping(value = "/producer", responseType = JsonBodyProducer.class)
        @DirectQueryInt(value = "items", defaultValue = 100, min = 1, max = 1000)
        public CompletableFuture<JsonBodyProducer> get(int items) {
            return CompletableFuture.completedFuture((out, offset) -> 0);
        }
    }

    static class ValidDirectBodylessJniAdmissionHandler {
        @GetMapping(value = "/direct", responseType = String.class)
        @JniQueueAdmission(maxPending = 96, queueTimeoutMs = 75)
        public int get(ByteBuffer out, int offset) {
            return 0;
        }
    }

    static class WorkloadBudgetHandler {
        @GetMapping(value = "/heavy", responseType = String.class)
        @RouteWorkload(value = RouteWorkload.Type.HEAVY_JSON, budget = "heavy-json-direct")
        @RouteAdmission(maxConcurrent = 96, queueTimeoutMs = 75)
        public int get(ByteBuffer out, int offset) {
            return 0;
        }
    }

    static class BenchmarkOnlyHandler {
        @GetMapping(value = "/heavy/legacy", responseType = String.class)
        @RouteWorkload(value = RouteWorkload.Type.HEAVY_JSON, budget = "heavy-json-legacy")
        @BenchmarkOnlyRoute("legacy comparison")
        public String get() {
            return "legacy";
        }
    }

    @Test
    void directPathPrimitiveMustReferenceRouteVariable() throws Exception {
        Method scanHandler = RouteScanner.class.getDeclaredMethod("scanHandler", Object.class, java.util.List.class);
        scanHandler.setAccessible(true);

        InvocationTargetException error = assertThrows(
                InvocationTargetException.class,
                () -> scanHandler.invoke(null, new InvalidDirectPathHandler(), new ArrayList<RouteDef>())
        );

        Throwable cause = error.getCause();
        assertInstanceOf(IllegalArgumentException.class, cause);
        assertTrue(cause.getMessage().contains("@DirectPathInt variable 'missing' is not present"));
    }

    @Test
    void nativeStaticRouteDoesNotAllowPathVariables() throws Exception {
        Method scanHandler = RouteScanner.class.getDeclaredMethod("scanHandler", Object.class, java.util.List.class);
        scanHandler.setAccessible(true);

        InvocationTargetException error = assertThrows(
                InvocationTargetException.class,
                () -> scanHandler.invoke(null, new InvalidNativeStaticPathHandler(), new ArrayList<RouteDef>())
        );

        Throwable cause = error.getCause();
        assertInstanceOf(IllegalArgumentException.class, cause);
        assertTrue(cause.getMessage().contains("@NativeStaticRoute does not support path variables"));
    }

    @Test
    void nativeStaticRouteRequiresRawResponseReturn() throws Exception {
        Method scanHandler = RouteScanner.class.getDeclaredMethod("scanHandler", Object.class, java.util.List.class);
        scanHandler.setAccessible(true);

        InvocationTargetException error = assertThrows(
                InvocationTargetException.class,
                () -> scanHandler.invoke(null, new InvalidNativeStaticReturnHandler(), new ArrayList<RouteDef>())
        );

        Throwable cause = error.getCause();
        assertInstanceOf(IllegalArgumentException.class, cause);
        assertTrue(cause.getMessage().contains("@NativeStaticRoute requires return type and responseType RawResponse.class"));
    }

    @Test
    void directQueryIntAllowsJsonProducerScalarSignature() throws Exception {
        Method scanHandler = RouteScanner.class.getDeclaredMethod("scanHandler", Object.class, java.util.List.class);
        scanHandler.setAccessible(true);
        ArrayList<RouteDef> routes = new ArrayList<>();

        scanHandler.invoke(null, new ValidDirectQueryProducerHandler(), routes);

        assertEquals(1, routes.size());
        RouteDef route = routes.get(0);
        assertEquals("items", route.directQueryIntName);
        assertEquals(100, route.directQueryIntDefault);
        assertEquals(1, route.directQueryIntMin);
        assertEquals(1000, route.directQueryIntMax);
    }

    @Test
    void directQueryIntAllowsJsonBodyProducerScalarSignature() throws Exception {
        Method scanHandler = RouteScanner.class.getDeclaredMethod("scanHandler", Object.class, java.util.List.class);
        scanHandler.setAccessible(true);
        ArrayList<RouteDef> routes = new ArrayList<>();

        scanHandler.invoke(null, new ValidDirectQueryBodyProducerHandler(), routes);

        assertEquals(1, routes.size());
        RouteDef route = routes.get(0);
        assertEquals("items", route.directQueryIntName);
        assertEquals(100, route.directQueryIntDefault);
        assertEquals(1, route.directQueryIntMin);
        assertEquals(1000, route.directQueryIntMax);
    }

    @Test
    void directQueryIntAllowsRawResponseScalarSignature() throws Exception {
        Method scanHandler = RouteScanner.class.getDeclaredMethod("scanHandler", Object.class, java.util.List.class);
        scanHandler.setAccessible(true);
        ArrayList<RouteDef> routes = new ArrayList<>();

        scanHandler.invoke(null, new ValidDirectQueryRawResponseHandler(), routes);

        assertEquals(1, routes.size());
        RouteDef route = routes.get(0);
        assertEquals("items", route.directQueryIntName);
        assertEquals(100, route.directQueryIntDefault);
        assertEquals(1, route.directQueryIntMin);
        assertEquals(1000, route.directQueryIntMax);
    }

    @Test
    void directQueryPrimitiveOutputIsNotHeavyJsonObjectGraph() throws Exception {
        Method scanHandler = RouteScanner.class.getDeclaredMethod("scanHandler", Object.class, java.util.List.class);
        scanHandler.setAccessible(true);
        ArrayList<RouteDef> routes = new ArrayList<>();

        scanHandler.invoke(null, new ValidDirectQueryPrimitiveOutputHandler(), routes);

        assertEquals(1, routes.size());
        RouteExecutionPlan plan = RoutePlanRegistry.getInstance().plans().get(0);
        assertTrue(plan.directPrimitiveOutput);
        assertFalse(plan.heavyJsonObjectGraph());
    }

    @Test
    void directPathIntAllowsRawResponseScalarSignature() throws Exception {
        Method scanHandler = RouteScanner.class.getDeclaredMethod("scanHandler", Object.class, java.util.List.class);
        scanHandler.setAccessible(true);
        ArrayList<RouteDef> routes = new ArrayList<>();

        scanHandler.invoke(null, new ValidDirectPathRawResponseHandler(), routes);

        assertEquals(1, routes.size());
        RouteDef route = routes.get(0);
        assertEquals("items", route.directPathIntName);
        assertEquals(Integer.MIN_VALUE, route.directPathIntMin);
        assertEquals(Integer.MAX_VALUE, route.directPathIntMax);
    }

    @Test
    void directQueryIntAllowsAsyncJsonBodyProducerScalarSignature() throws Exception {
        Method scanHandler = RouteScanner.class.getDeclaredMethod("scanHandler", Object.class, java.util.List.class);
        scanHandler.setAccessible(true);
        ArrayList<RouteDef> routes = new ArrayList<>();

        scanHandler.invoke(null, new ValidDirectQueryAsyncBodyProducerHandler(), routes);

        assertEquals(1, routes.size());
        RouteDef route = routes.get(0);
        assertTrue(route.asyncRoute);
        assertEquals("items", route.directQueryIntName);
        assertEquals(100, route.directQueryIntDefault);
        assertEquals(1, route.directQueryIntMin);
        assertEquals(1000, route.directQueryIntMax);
    }

    @Test
    void jniQueueAdmissionIsPreservedForDirectBodylessRoutes() throws Exception {
        Method scanHandler = RouteScanner.class.getDeclaredMethod("scanHandler", Object.class, java.util.List.class);
        scanHandler.setAccessible(true);
        ArrayList<RouteDef> routes = new ArrayList<>();

        scanHandler.invoke(null, new ValidDirectBodylessJniAdmissionHandler(), routes);

        assertEquals(1, routes.size());
        RouteDef route = routes.get(0);
        assertTrue(route.bodyless);
        assertTrue(route.directBodylessOutput);
        assertEquals(0, route.admissionMaxConcurrent);
        assertEquals(0, route.admissionQueueTimeoutMs);
        assertEquals(96, route.jniAdmissionMaxPending);
        assertEquals(75, route.jniAdmissionQueueTimeoutMs);
    }

    @Test
    void workloadBudgetOverridesAnnotationAdmission() throws Exception {
        Method scanHandler = RouteScanner.class.getDeclaredMethod("scanHandler", Object.class, java.util.List.class);
        scanHandler.setAccessible(true);
        ArrayList<RouteDef> routes = new ArrayList<>();

        System.setProperty("reactor.rust.route-budget.heavy-json-direct.route-admission.max-concurrent", "80");
        System.setProperty("reactor.rust.route-budget.heavy-json-direct.route-admission.queue-timeout-ms", "150");
        try {
            scanHandler.invoke(null, new WorkloadBudgetHandler(), routes);
        } finally {
            System.clearProperty("reactor.rust.route-budget.heavy-json-direct.route-admission.max-concurrent");
            System.clearProperty("reactor.rust.route-budget.heavy-json-direct.route-admission.queue-timeout-ms");
        }

        assertEquals(1, routes.size());
        RouteDef route = routes.get(0);
        assertEquals(80, route.admissionMaxConcurrent);
        assertEquals(150, route.admissionQueueTimeoutMs);
    }

    @Test
    void routeSpecificAdmissionOverrideWinsOverWorkloadBudget() throws Exception {
        Method scanHandler = RouteScanner.class.getDeclaredMethod("scanHandler", Object.class, java.util.List.class);
        scanHandler.setAccessible(true);
        ArrayList<RouteDef> routes = new ArrayList<>();

        System.setProperty("reactor.rust.route-budget.heavy-json-direct.route-admission.max-concurrent", "80");
        System.setProperty("reactor.rust.route-budget.heavy-json-direct.route-admission.queue-timeout-ms", "150");
        System.setProperty("reactor.rust.route-admission.get.heavy.max-concurrent", "64");
        System.setProperty("reactor.rust.route-admission.get.heavy.queue-timeout-ms", "25");
        try {
            scanHandler.invoke(null, new WorkloadBudgetHandler(), routes);
        } finally {
            System.clearProperty("reactor.rust.route-budget.heavy-json-direct.route-admission.max-concurrent");
            System.clearProperty("reactor.rust.route-budget.heavy-json-direct.route-admission.queue-timeout-ms");
            System.clearProperty("reactor.rust.route-admission.get.heavy.max-concurrent");
            System.clearProperty("reactor.rust.route-admission.get.heavy.queue-timeout-ms");
        }

        assertEquals(1, routes.size());
        RouteDef route = routes.get(0);
        assertEquals(64, route.admissionMaxConcurrent);
        assertEquals(25, route.admissionQueueTimeoutMs);
    }

    @Test
    void benchmarkOnlyRouteIsPreservedInRoutePlanDiagnostics() throws Exception {
        Method scanHandler = RouteScanner.class.getDeclaredMethod("scanHandler", Object.class, java.util.List.class);
        scanHandler.setAccessible(true);
        ArrayList<RouteDef> routes = new ArrayList<>();

        scanHandler.invoke(null, new BenchmarkOnlyHandler(), routes);

        assertEquals(1, routes.size());
        RouteExecutionPlan plan = RoutePlanRegistry.getInstance().plans().get(0);
        assertTrue(plan.benchmarkOnly);
        assertFalse(plan.productionRoute());
        assertTrue(plan.heavyJsonObjectGraph());
    }
}
