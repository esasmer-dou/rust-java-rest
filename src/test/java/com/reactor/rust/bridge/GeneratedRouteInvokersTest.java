package com.reactor.rust.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class GeneratedRouteInvokersTest {

    static final class Endpoint {
        String find(long id) {
            return Long.toString(id);
        }
    }

    @AfterEach
    void clear() {
        GeneratedRouteInvokers.releaseStartupMetadata();
    }

    @Test
    void indexesGeneratedRoutesByOwnerAndCarriesMappingMetadata() {
        GeneratedRouteInvoker invoker = new GeneratedRouteInvoker() {
            @Override
            public int arity() {
                return 1;
            }

            @Override
            public Object invoke1(Object bean, Object arg0) {
                return ((Endpoint) bean).find((Long) arg0);
            }
        };
        GeneratedRouteMetadata metadata = new GeneratedRouteMetadata(
                "GET", "/customers/{id}", Void.class, String.class, 0L, 4096L);
        GeneratedRouteInvokers.register(
                Endpoint.class, "find", new Class<?>[] {long.class}, invoker, metadata);

        Method method = GeneratedRouteInvokers.routeMethods(Endpoint.class)[0];

        assertEquals("find", method.getName());
        assertSame(invoker, GeneratedRouteInvokers.find(method));
        assertEquals(metadata, GeneratedRouteInvokers.metadata(method));
        assertEquals(1, GeneratedRouteInvokers.size());
    }
}
