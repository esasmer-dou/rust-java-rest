package com.reactor.rust.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RequestValueMapTest {

    @Test
    void caseInsensitiveLookupWorksForMixedCaseKeys() {
        RequestValueMap map = new RequestValueMap();
        map.put("X-Request-ID", "abc-123");

        assertEquals("abc-123", map.getIgnoreCase("x-request-id"));
        assertEquals("abc-123", map.getIgnoreCase("X-REQUEST-id"));
        assertNull(map.getIgnoreCase("missing"));
    }

    @Test
    void oversizedBackingArraysAreReleasedOnClear() {
        RequestValueMap map = new RequestValueMap();
        for (int i = 0; i < 80; i++) {
            map.put("key-" + i, "value-" + i);
        }

        map.clear();

        assertEquals(16, map.retainedCapacity());
        assertEquals(0, map.size());
    }
}
