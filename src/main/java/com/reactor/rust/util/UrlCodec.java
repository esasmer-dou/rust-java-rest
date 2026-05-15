package com.reactor.rust.util;

import java.nio.charset.StandardCharsets;

/**
 * Minimal UTF-8 URL component decoder for hot request paths.
 *
 * <p>Fast path returns the original String when no decoding is needed. Query
 * parameters may use '+' for space; path parameters must keep '+' literal.</p>
 */
public final class UrlCodec {

    private UrlCodec() {}

    public static String decodeComponent(String value, boolean plusAsSpace) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        int len = value.length();
        boolean needsDecode = false;
        for (int i = 0; i < len; i++) {
            char ch = value.charAt(i);
            if (ch == '%' || (plusAsSpace && ch == '+')) {
                needsDecode = true;
                break;
            }
        }
        if (!needsDecode) {
            return value;
        }

        StringBuilder decoded = new StringBuilder(len);
        byte[] bytes = null;
        int i = 0;
        while (i < len) {
            char ch = value.charAt(i);
            if (ch == '%' && i + 2 < len) {
                int hi = hex(value.charAt(i + 1));
                int lo = hex(value.charAt(i + 2));
                if (hi >= 0 && lo >= 0) {
                    if (bytes == null) {
                        bytes = new byte[len];
                    }
                    int count = 0;
                    while (i + 2 < len && value.charAt(i) == '%') {
                        hi = hex(value.charAt(i + 1));
                        lo = hex(value.charAt(i + 2));
                        if (hi < 0 || lo < 0) {
                            break;
                        }
                        bytes[count++] = (byte) ((hi << 4) + lo);
                        i += 3;
                    }
                    decoded.append(new String(bytes, 0, count, StandardCharsets.UTF_8));
                    continue;
                }
            }

            decoded.append(plusAsSpace && ch == '+' ? ' ' : ch);
            i++;
        }
        return decoded.toString();
    }

    private static int hex(char ch) {
        if (ch >= '0' && ch <= '9') {
            return ch - '0';
        }
        if (ch >= 'A' && ch <= 'F') {
            return ch - 'A' + 10;
        }
        if (ch >= 'a' && ch <= 'f') {
            return ch - 'a' + 10;
        }
        return -1;
    }
}
