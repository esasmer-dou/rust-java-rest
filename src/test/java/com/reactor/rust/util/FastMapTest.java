package com.reactor.rust.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class FastMapTest {

    @Test
    void pathAndQueryPoolsAreIndependent() {
        assertNotSame(PooledMaps.getPathParams(), PooledMaps.getQueryParams());
    }

    @Test
    void oversizedBackingArraysAreReleasedOnClear() {
        FastMap map = new FastMap();
        for (int i = 0; i < 80; i++) {
            map.put("key-" + i, "value-" + i);
        }

        map.clear();

        assertEquals(16, map.retainedCapacity());
        assertEquals(0, map.size());
    }
}
