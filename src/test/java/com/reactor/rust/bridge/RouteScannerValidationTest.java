package com.reactor.rust.bridge;

import com.reactor.rust.annotations.RouteWorkload;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RouteScannerValidationTest {

    @RouteWorkload(value = RouteWorkload.Type.RPC_READ, budget = "rpc-read")
    static final class WorkloadHandler {
        void inherited() {}

        @RouteWorkload(value = RouteWorkload.Type.RPC_COMMAND, budget = "rpc-command")
        void overridden() {}
    }

    static final class GuardedHandler {
        void normal() {}
    }

    @Test
    void rejectsDuplicateMethodAndPathBeforeNativePublication() {
        RouteDef first = new RouteDef("GET", "/api/items", 1, Void.class.getName(), String.class.getName());
        RouteDef duplicate = new RouteDef("get", "/api/items", 2, Void.class.getName(), String.class.getName());

        assertThrows(
                IllegalStateException.class,
                () -> RouteScanner.validateUniqueRoutes(List.of(first, duplicate))
        );
    }

    @Test
    void allowsSamePathForDifferentHttpMethods() {
        RouteDef get = new RouteDef("GET", "/api/items", 1, Void.class.getName(), String.class.getName());
        RouteDef post = new RouteDef("POST", "/api/items", 2, byte[].class.getName(), String.class.getName());

        assertDoesNotThrow(() -> RouteScanner.validateUniqueRoutes(List.of(get, post)));
    }

    @Test
    void methodWorkloadOverridesClassDefault() throws Exception {
        Method inherited = WorkloadHandler.class.getDeclaredMethod("inherited");
        Method overridden = WorkloadHandler.class.getDeclaredMethod("overridden");

        assertEquals(
                RouteWorkload.Type.RPC_READ,
                RouteScanner.effectiveRouteWorkload(WorkloadHandler.class, inherited).value()
        );
        assertEquals(
                RouteWorkload.Type.RPC_COMMAND,
                RouteScanner.effectiveRouteWorkload(WorkloadHandler.class, overridden).value()
        );
    }

    @Test
    void composesRequestGuardsInReverseCleanupOrder() throws Exception {
        ArrayList<String> calls = new ArrayList<>();
        RequestGuardFactory first = (owner, method) -> guard("first", calls);
        RequestGuardFactory second = (owner, method) -> guard("second", calls);
        Method method = GuardedHandler.class.getDeclaredMethod("normal");

        RequestGuard guard = RouteScanner.requestGuard(List.of(first, second), GuardedHandler.class, method);
        guard.before(new RequestGuardContext("", "", "authorization: Bearer test\n", new byte[0]));
        guard.after();

        assertEquals(List.of("first:before", "second:before", "second:after", "first:after"), calls);
    }

    @Test
    void rejectsGuardOnHeaderlessSpecializedNativePath() throws Exception {
        Method method = GuardedHandler.class.getDeclaredMethod("normal");

        assertThrows(IllegalStateException.class, () -> RouteScanner.validateGuardCompatibleRoute(
                method,
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                0,
                0
        ));
    }

    private static RequestGuard guard(String name, List<String> calls) {
        return new RequestGuard() {
            @Override
            public void before(RequestGuardContext request) {
                calls.add(name + ":before");
            }

            @Override
            public void after() {
                calls.add(name + ":after");
            }
        };
    }
}
