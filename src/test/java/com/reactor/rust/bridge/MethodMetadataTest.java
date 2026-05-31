package com.reactor.rust.bridge;

import com.reactor.rust.annotations.CookieValue;
import com.reactor.rust.annotations.HeaderParam;
import com.reactor.rust.annotations.PathVariable;
import com.reactor.rust.annotations.RequestParam;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class MethodMetadataTest {

    static class AnnotatedHandler {
        public String route(
                @PathVariable("id") int id,
                @PathVariable("slug") String slug,
                @RequestParam("page") int page,
                @RequestParam("filter") String filter,
                @HeaderParam("X-Trace") String trace,
                @CookieValue("session") String session
        ) {
            return id + slug + page + filter + trace + session;
        }
    }

    @Test
    void metadataPrecomputesOnlyConsumedParamAndHeaderNames() throws Exception {
        Method method = AnnotatedHandler.class.getDeclaredMethod(
                "route",
                int.class,
                String.class,
                int.class,
                String.class,
                String.class,
                String.class
        );

        MethodMetadata.clearCache();
        MethodMetadata metadata = MethodMetadata.getOrCreate(method, Void.class, String.class);

        assertArrayEquals(new String[] {"id", "slug"}, metadata.pathParamNames);
        assertArrayEquals(new String[] {"page", "filter"}, metadata.queryParamNames);
        assertArrayEquals(new String[] {"x-trace", "cookie"}, metadata.headerNames);
    }
}
