package com.reactor.rust.bridge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RouteScannerValidationTest {

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
}
