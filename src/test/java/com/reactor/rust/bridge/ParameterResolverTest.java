package com.reactor.rust.bridge;

import com.reactor.rust.annotations.PathVariable;
import com.reactor.rust.annotations.RequestParam;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class ParameterResolverTest {

    static class MixedParameterHandler {
        public void handle(
                @PathVariable("id") String pathId,
                @RequestParam("id") String queryId
        ) {}
    }

    @Test
    void keepsPathAndQueryValuesIndependentWhenNamesCollide() throws Exception {
        Method method = MixedParameterHandler.class.getDeclaredMethod("handle", String.class, String.class);

        Object[] resolved = ParameterResolver.resolveParameters(
                method,
                new byte[0],
                "id=path-value",
                "id=query-value",
                ""
        );

        assertArrayEquals(new Object[] {"path-value", "query-value"}, resolved);
    }
}
