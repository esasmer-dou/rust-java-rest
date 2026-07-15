package com.reactor.rust.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FastMapV2Test {

    @Test
    void clearRemovesOnlyLiveEntriesAndMapRemainsReusableAfterResize() {
        FastMapV2 map = new FastMapV2(4);
        for (int i = 0; i < 64; i++) {
            map.put("k" + i, "v" + i);
        }
        assertEquals("v42", map.get("k42"));

        map.clear();
        assertEquals(0, map.size());
        assertNull(map.get("k42"));

        map.put("next", "value");
        assertEquals("value", map.get("next"));
        assertNull(map.get("k1"));
    }

    @Test
    void replacingExistingKeyDoesNotLeaveStaleSlotAfterClear() {
        FastMapV2 map = new FastMapV2();
        map.put("city", "ankara");
        map.put("city", "istanbul");

        assertEquals(1, map.size());
        assertEquals("istanbul", map.get("city"));

        map.clear();
        assertNull(map.get("city"));
        assertEquals(0, map.size());
    }

    @Test
    void acquireClearsValuesFromThePreviousLease() {
        FastMapV2 first = FastMapV2.acquire();
        first.put("request", "one");

        FastMapV2 second = FastMapV2.acquire();

        assertEquals(first, second);
        assertEquals(0, second.size());
        assertNull(second.get("request"));
    }
}
