package com.reactor.rust.bridge;

import com.reactor.rust.annotations.DirectPathInt;
import com.reactor.rust.annotations.GetMapping;
import com.reactor.rust.annotations.NativeStaticRoute;
import com.reactor.rust.http.RawResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;

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
}
