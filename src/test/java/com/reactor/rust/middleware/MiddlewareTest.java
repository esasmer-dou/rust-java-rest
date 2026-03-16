package com.reactor.rust.middleware;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Middleware, MiddlewareChain, and MiddlewareContext.
 */
class MiddlewareTest {

    @Test
    @DisplayName("MiddlewareContext stores request data")
    void testMiddlewareContextRequestData() {
        Map<String, String> headers = Map.of("content-type", "application/json");
        Map<String, String> pathParams = Map.of("id", "123");

        MiddlewareContext context = new MiddlewareContext(
            "POST", "/api/users/123", "debug=true",
            headers, pathParams, "{\"name\":\"John\"}".getBytes()
        );

        assertEquals("POST", context.method());
        assertEquals("POST", context.getMethod());
        assertEquals("/api/users/123", context.path());
        assertEquals("debug=true", context.queryString());
        assertEquals("application/json", context.getHeader("content-type"));
        assertEquals("123", context.getPathParam("id"));
        assertNotNull(context.body());
    }

    @Test
    @DisplayName("MiddlewareContext getHeader with default value")
    void testMiddlewareContextGetHeaderDefault() {
        MiddlewareContext context = new MiddlewareContext(
            "GET", "/test", null,
            Map.of(), Map.of(), null
        );

        assertEquals("default", context.getHeader("x-custom", "default"));
        assertNull(context.getHeader("x-custom"));
    }

    @Test
    @DisplayName("MiddlewareContext getPathParam with default value")
    void testMiddlewareContextGetPathParamDefault() {
        MiddlewareContext context = new MiddlewareContext(
            "GET", "/test", null,
            Map.of(), Map.of(), null
        );

        assertEquals("default", context.getPathParam("missing", "default"));
        assertNull(context.getPathParam("missing"));
    }

    @Test
    @DisplayName("MiddlewareContext getQueryParam parses query string")
    void testMiddlewareContextGetQueryParam() {
        MiddlewareContext context = new MiddlewareContext(
            "GET", "/search", "q=hello&page=2&sort=desc",
            Map.of(), Map.of(), null
        );

        assertEquals("hello", context.getQueryParam("q"));
        assertEquals("2", context.getQueryParam("page"));
        assertEquals("desc", context.getQueryParam("sort"));
        assertNull(context.getQueryParam("missing"));
    }

    @Test
    @DisplayName("MiddlewareContext getQueryParam with default value")
    void testMiddlewareContextGetQueryParamDefault() {
        MiddlewareContext context = new MiddlewareContext(
            "GET", "/search", "q=test",
            Map.of(), Map.of(), null
        );

        assertEquals("test", context.getQueryParam("q", "default"));
        assertEquals("default", context.getQueryParam("missing", "default"));
    }

    @Test
    @DisplayName("MiddlewareContext handles null query string")
    void testMiddlewareContextNullQueryString() {
        MiddlewareContext context = new MiddlewareContext(
            "GET", "/test", null,
            Map.of(), Map.of(), null
        );

        assertNull(context.getQueryParam("any"));
        assertEquals("default", context.getQueryParam("any", "default"));
    }

    @Test
    @DisplayName("MiddlewareContext attributes")
    void testMiddlewareContextAttributes() {
        MiddlewareContext context = new MiddlewareContext(
            "GET", "/test", null,
            Map.of(), Map.of(), null
        );

        assertFalse(context.hasAttribute("user"));
        context.setAttribute("user", "john");
        assertTrue(context.hasAttribute("user"));
        assertEquals("john", context.getAttribute("user"));

        context.removeAttribute("user");
        assertFalse(context.hasAttribute("user"));
    }

    @Test
    @DisplayName("MiddlewareChain executes in order")
    void testMiddlewareChainOrder() {
        StringBuilder order = new StringBuilder();

        Middleware first = new Middleware() {
            @Override
            public int getOrder() { return 1; }
            @Override
            public MiddlewareChain.Result process(MiddlewareContext context, MiddlewareChain chain) {
                order.append("1");
                return chain.next(context);
            }
        };

        Middleware second = new Middleware() {
            @Override
            public int getOrder() { return 2; }
            @Override
            public MiddlewareChain.Result process(MiddlewareContext context, MiddlewareChain chain) {
                order.append("2");
                return chain.next(context);
            }
        };

        MiddlewareChain chain = MiddlewareChain.builder()
            .add(second)
            .add(first)
            .terminalHandler(ctx -> {
                order.append("H");
                return new MiddlewareChain.Result.Continue();
            })
            .build();

        chain.next(new MiddlewareContext("GET", "/", null, Map.of(), Map.of(), null));

        assertEquals("12H", order.toString());
    }

    @Test
    @DisplayName("MiddlewareChain can short-circuit with response")
    void testMiddlewareChainShortCircuit() {
        Middleware blocking = (context, chain) ->
            MiddlewareChain.Result.Response.unauthorized("{\"error\":\"Unauthorized\"}");

        MiddlewareChain chain = MiddlewareChain.builder()
            .add(blocking)
            .terminalHandler(ctx -> {
                fail("Should not reach terminal handler");
                return new MiddlewareChain.Result.Continue();
            })
            .build();

        MiddlewareChain.Result result = chain.next(
            new MiddlewareContext("GET", "/protected", null, Map.of(), Map.of(), null)
        );

        assertTrue(result instanceof MiddlewareChain.Result.Response);
        MiddlewareChain.Result.Response response = (MiddlewareChain.Result.Response) result;
        assertEquals(401, response.status());
        assertEquals("{\"error\":\"Unauthorized\"}", response.body());
    }

    @Test
    @DisplayName("MiddlewareChain Continue result")
    void testMiddlewareChainContinueResult() {
        MiddlewareChain.Result.Continue continueResult = new MiddlewareChain.Result.Continue();

        assertTrue(continueResult instanceof MiddlewareChain.Result);
    }

    @Test
    @DisplayName("MiddlewareChain Response static factory methods")
    void testMiddlewareChainResponseFactories() {
        assertEquals(200, MiddlewareChain.Result.Response.ok("ok").status());
        assertEquals(201, MiddlewareChain.Result.Response.created("created").status());
        assertEquals(400, MiddlewareChain.Result.Response.badRequest("bad").status());
        assertEquals(401, MiddlewareChain.Result.Response.unauthorized("unauth").status());
        assertEquals(403, MiddlewareChain.Result.Response.forbidden("forbidden").status());
        assertEquals(404, MiddlewareChain.Result.Response.notFound("not found").status());
        assertEquals(500, MiddlewareChain.Result.Response.internalError("error").status());
    }

    @Test
    @DisplayName("MiddlewareChain ResponseWithHeaders")
    void testMiddlewareChainResponseWithHeaders() {
        Map<String, String> headers = Map.of("X-Custom", "value");
        MiddlewareChain.Result.ResponseWithHeaders result =
            MiddlewareChain.Result.ResponseWithHeaders.of(200, "body", headers);

        assertEquals(200, result.status());
        assertEquals("body", result.body());
        assertEquals(headers, result.headers());
    }

    @Test
    @DisplayName("MiddlewareChain empty chain executes terminal handler")
    void testMiddlewareChainEmpty() {
        boolean[] handlerCalled = {false};

        MiddlewareChain chain = MiddlewareChain.builder()
            .terminalHandler(ctx -> {
                handlerCalled[0] = true;
                return new MiddlewareChain.Result.Continue();
            })
            .build();

        chain.next(new MiddlewareContext("GET", "/", null, Map.of(), Map.of(), null));

        assertTrue(handlerCalled[0]);
    }

    @Test
    @DisplayName("Middleware can modify context via attributes")
    void testMiddlewareModifiesContext() {
        Middleware authMiddleware = (context, chain) -> {
            context.setAttribute("userId", "user-123");
            return chain.next(context);
        };

        MiddlewareChain chain = MiddlewareChain.builder()
            .add(authMiddleware)
            .terminalHandler(ctx -> {
                String userId = (String) ctx.getAttribute("userId");
                return MiddlewareChain.Result.Response.ok("{\"userId\":\"" + userId + "\"}");
            })
            .build();

        MiddlewareChain.Result result = chain.next(
            new MiddlewareContext("GET", "/", null, Map.of(), Map.of(), null)
        );

        assertTrue(result instanceof MiddlewareChain.Result.Response);
        MiddlewareChain.Result.Response response = (MiddlewareChain.Result.Response) result;
        assertTrue(response.body().contains("user-123"));
    }

    @Test
    @DisplayName("Default middleware order is 100")
    void testDefaultMiddlewareOrder() {
        Middleware middleware = (context, chain) -> chain.next(context);
        assertEquals(100, middleware.getOrder());
    }
}
