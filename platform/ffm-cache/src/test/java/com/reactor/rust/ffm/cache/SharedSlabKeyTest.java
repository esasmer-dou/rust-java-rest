package com.reactor.rust.ffm.cache;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class SharedSlabKeyTest {
    @Test void boundedStrictUtf8LengthIsIndependentOfSelectedBackend() {
        for (String key : new String[]{"customers:42", "\u00c7a\u011fr\u0131", "\ud83d\ude00", "a\0b"}) {
            int size = key.getBytes(StandardCharsets.UTF_8).length;
            assertEquals(size, SharedSlabCacheReader.validateKey(key, size));
            assertThrows(IllegalArgumentException.class, () -> SharedSlabCacheReader.validateKey(key, size - 1));
        }
        for (String key : new String[]{"", "\ud800", "\udc00", "x\ud800x"}) {
            assertThrows(IllegalArgumentException.class, () -> SharedSlabCacheReader.validateKey(key, 1024));
        }
        assertThrows(NullPointerException.class, () -> SharedSlabCacheReader.validateKey(null, 1024));
    }
}
