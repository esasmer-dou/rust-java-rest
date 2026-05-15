package com.reactor.rust.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class UrlCodecTest {

    @Test
    void returnsSameInstanceWhenDecodeIsNotNeeded() {
        String value = "plain-ascii";

        assertSame(value, UrlCodec.decodeComponent(value, true));
    }

    @Test
    void decodesUtf8PercentEncodedComponent() {
        assertEquals(
                "İstanbul şeker ölçü",
                UrlCodec.decodeComponent("%C4%B0stanbul%20%C5%9Feker%20%C3%B6l%C3%A7%C3%BC", false)
        );
    }

    @Test
    void queryDecodingTreatsPlusAsSpaceButPathDoesNot() {
        assertEquals("Mustafa Korkmaz", UrlCodec.decodeComponent("Mustafa+Korkmaz", true));
        assertEquals("Mustafa+Korkmaz", UrlCodec.decodeComponent("Mustafa+Korkmaz", false));
    }
}
