package com.reactor.rust.bridge;

import com.reactor.rust.annotations.DirectPathInt;
import com.reactor.rust.annotations.DirectQueryInt;
import com.reactor.rust.annotations.GetMapping;
import com.reactor.rust.annotations.NativeStaticRoute;
import com.reactor.rust.http.JsonProducerResponse;
import com.reactor.rust.http.RawResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteScannerValidationTest {

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
}
