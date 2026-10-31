package com.reactor.rust.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RawResponseOwnershipTest {
    @Test
    void heapAndBorrowedNativeResponsesNeverAcquireAbortOwnership() {
        RawResponse heap = RawResponse.json(new byte[]{1, 2, 3});
        RawResponse borrowed = RawResponse.nativeResponse(17);
        RawResponse borrowedJson = RawResponse.nativeJson(18);
        for (RawResponse response : new RawResponse[]{heap, borrowed, borrowedJson}) {
            assertFalse(response.ownsNativeResponse());
            assertDoesNotThrow(response::close);
            assertDoesNotThrow(response::close);
            assertFalse(response.ownsNativeResponse());
        }
        assertArrayEquals(new byte[]{1, 2, 3}, heap.getBody());
        assertEquals(17, borrowed.getNativeId());
    }

    @Test
    void onlyExplicitTransientOwnershipRequiresAbortCleanup() {
        RawResponse owned = RawResponse.ownedNativeResponse(19);
        assertTrue(owned.ownsNativeResponse());
        assertEquals(19, owned.getNativeId());
        assertThrows(IllegalArgumentException.class, () -> RawResponse.ownedNativeResponse(0));
        assertThrows(IllegalArgumentException.class, () -> RawResponse.ownedNativeResponse(-1));
    }
}
