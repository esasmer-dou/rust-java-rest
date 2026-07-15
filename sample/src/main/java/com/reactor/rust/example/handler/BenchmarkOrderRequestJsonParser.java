package com.reactor.rust.example.handler;

import com.reactor.rust.example.handler.BenchmarkHandler.BenchmarkAddress;
import com.reactor.rust.example.handler.BenchmarkHandler.BenchmarkCustomer;
import com.reactor.rust.example.handler.BenchmarkHandler.BenchmarkItem;
import com.reactor.rust.example.handler.BenchmarkHandler.BenchmarkOrderRequest;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Generated-style parser prototype for {@link BenchmarkOrderRequest}.
 *
 * <p>This is intentionally schema-specific: no reflection, no generic field map and no
 * intermediate {@code byte[]} body copy. It is the shape an annotation processor should
 * generate for hot DTOs.</p>
 */
final class BenchmarkOrderRequestJsonParser {

    private BenchmarkOrderRequestJsonParser() {}

    static BenchmarkOrderRequest parse(ByteBuffer body, int length) {
        if (body == null || length <= 0) {
            return null;
        }
        ByteBuffer duplicate = body.duplicate();
        duplicate.position(0);
        duplicate.limit(Math.min(length, duplicate.capacity()));
        return new Parser(duplicate).parseOrder();
    }

    private static final class Parser {
        private final ByteBuffer body;
        private final int limit;
        private int pos;

        private Parser(ByteBuffer body) {
            this.body = body;
            this.limit = body.limit();
        }

        private BenchmarkOrderRequest parseOrder() {
            skipWs();
            if (consumeNull()) {
                return null;
            }
            expect('{');

            String orderId = null;
            double amount = 0.0d;
            boolean paid = false;
            BenchmarkAddress address = null;
            BenchmarkCustomer customer = null;
            List<BenchmarkItem> items = null;

            skipWs();
            if (consume('}')) {
                return new BenchmarkOrderRequest(orderId, amount, paid, address, customer, items);
            }

            while (true) {
                String name = readString();
                skipWs();
                expect(':');
                skipWs();

                switch (name) {
                    case "orderId" -> orderId = readNullableString();
                    case "amount" -> amount = readDouble();
                    case "paid" -> paid = readBoolean();
                    case "address" -> address = parseAddress();
                    case "customer" -> customer = parseCustomer();
                    case "items" -> items = parseItems();
                    default -> skipValue();
                }

                skipWs();
                if (consume('}')) {
                    break;
                }
                expect(',');
                skipWs();
            }

            return new BenchmarkOrderRequest(orderId, amount, paid, address, customer, items);
        }

        private BenchmarkAddress parseAddress() {
            if (consumeNull()) {
                return null;
            }
            expect('{');

            String city = null;
            String street = null;
            skipWs();
            if (consume('}')) {
                return new BenchmarkAddress(city, street);
            }

            while (true) {
                String name = readString();
                skipWs();
                expect(':');
                skipWs();

                switch (name) {
                    case "city" -> city = readNullableString();
                    case "street" -> street = readNullableString();
                    default -> skipValue();
                }

                skipWs();
                if (consume('}')) {
                    break;
                }
                expect(',');
                skipWs();
            }

            return new BenchmarkAddress(city, street);
        }

        private BenchmarkCustomer parseCustomer() {
            if (consumeNull()) {
                return null;
            }
            expect('{');

            String nameValue = null;
            String email = null;
            skipWs();
            if (consume('}')) {
                return new BenchmarkCustomer(nameValue, email);
            }

            while (true) {
                String name = readString();
                skipWs();
                expect(':');
                skipWs();

                switch (name) {
                    case "name" -> nameValue = readNullableString();
                    case "email" -> email = readNullableString();
                    default -> skipValue();
                }

                skipWs();
                if (consume('}')) {
                    break;
                }
                expect(',');
                skipWs();
            }

            return new BenchmarkCustomer(nameValue, email);
        }

        private List<BenchmarkItem> parseItems() {
            if (consumeNull()) {
                return null;
            }
            expect('[');
            List<BenchmarkItem> items = new ArrayList<>();
            skipWs();
            if (consume(']')) {
                return items;
            }

            while (true) {
                items.add(parseItem());
                skipWs();
                if (consume(']')) {
                    break;
                }
                expect(',');
                skipWs();
            }
            return items;
        }

        private BenchmarkItem parseItem() {
            if (consumeNull()) {
                return null;
            }
            expect('{');

            String nameValue = null;
            double price = 0.0d;
            skipWs();
            if (consume('}')) {
                return new BenchmarkItem(nameValue, price);
            }

            while (true) {
                String name = readString();
                skipWs();
                expect(':');
                skipWs();

                switch (name) {
                    case "name" -> nameValue = readNullableString();
                    case "price" -> price = readDouble();
                    default -> skipValue();
                }

                skipWs();
                if (consume('}')) {
                    break;
                }
                expect(',');
                skipWs();
            }

            return new BenchmarkItem(nameValue, price);
        }

        private String readNullableString() {
            return consumeNull() ? null : readString();
        }

        private String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder(32);
            while (pos < limit) {
                int ch = readByte();
                if (ch == '"') {
                    return sb.toString();
                }
                if (ch == '\\') {
                    sb.append(readEscapedChar());
                    continue;
                }
                if (ch < 0x20) {
                    throw error("control character in string");
                }
                if (ch < 0x80) {
                    sb.append((char) ch);
                } else {
                    sb.appendCodePoint(readUtf8CodePoint(ch));
                }
            }
            throw error("unterminated string");
        }

        private char readEscapedChar() {
            int escaped = readByte();
            return switch (escaped) {
                case '"' -> '"';
                case '\\' -> '\\';
                case '/' -> '/';
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'u' -> (char) readHex4();
                default -> throw error("invalid escape");
            };
        }

        private int readUtf8CodePoint(int first) {
            if ((first & 0xE0) == 0xC0) {
                return ((first & 0x1F) << 6) | (readContinuation() & 0x3F);
            }
            if ((first & 0xF0) == 0xE0) {
                return ((first & 0x0F) << 12)
                        | ((readContinuation() & 0x3F) << 6)
                        | (readContinuation() & 0x3F);
            }
            if ((first & 0xF8) == 0xF0) {
                return ((first & 0x07) << 18)
                        | ((readContinuation() & 0x3F) << 12)
                        | ((readContinuation() & 0x3F) << 6)
                        | (readContinuation() & 0x3F);
            }
            throw error("invalid utf8");
        }

        private int readContinuation() {
            int ch = readByte();
            if ((ch & 0xC0) != 0x80) {
                throw error("invalid utf8 continuation");
            }
            return ch;
        }

        private int readHex4() {
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int ch = readByte();
                int digit;
                if (ch >= '0' && ch <= '9') {
                    digit = ch - '0';
                } else if (ch >= 'a' && ch <= 'f') {
                    digit = ch - 'a' + 10;
                } else if (ch >= 'A' && ch <= 'F') {
                    digit = ch - 'A' + 10;
                } else {
                    throw error("invalid unicode escape");
                }
                value = (value << 4) | digit;
            }
            return value;
        }

        private boolean readBoolean() {
            if (matchLiteral("true")) {
                pos += 4;
                return true;
            }
            if (matchLiteral("false")) {
                pos += 5;
                return false;
            }
            throw error("expected boolean");
        }

        private double readDouble() {
            int start = pos;
            while (pos < limit) {
                int ch = peek();
                if ((ch >= '0' && ch <= '9')
                        || ch == '-'
                        || ch == '+'
                        || ch == '.'
                        || ch == 'e'
                        || ch == 'E') {
                    pos++;
                    continue;
                }
                break;
            }
            if (start == pos) {
                throw error("expected number");
            }

            byte[] bytes = new byte[pos - start];
            ByteBuffer duplicate = body.duplicate();
            duplicate.position(start);
            duplicate.get(bytes);
            return Double.parseDouble(new String(bytes, java.nio.charset.StandardCharsets.US_ASCII));
        }

        private void skipValue() {
            skipWs();
            int ch = peek();
            switch (ch) {
                case '"' -> readString();
                case '{' -> skipObject();
                case '[' -> skipArray();
                case 't', 'f' -> readBoolean();
                case 'n' -> {
                    if (!consumeNull()) {
                        throw error("invalid null");
                    }
                }
                default -> readDouble();
            }
        }

        private void skipObject() {
            expect('{');
            skipWs();
            if (consume('}')) {
                return;
            }
            while (true) {
                readString();
                skipWs();
                expect(':');
                skipValue();
                skipWs();
                if (consume('}')) {
                    return;
                }
                expect(',');
                skipWs();
            }
        }

        private void skipArray() {
            expect('[');
            skipWs();
            if (consume(']')) {
                return;
            }
            while (true) {
                skipValue();
                skipWs();
                if (consume(']')) {
                    return;
                }
                expect(',');
                skipWs();
            }
        }

        private boolean consumeNull() {
            if (matchLiteral("null")) {
                pos += 4;
                return true;
            }
            return false;
        }

        private boolean matchLiteral(String literal) {
            if (pos + literal.length() > limit) {
                return false;
            }
            for (int i = 0; i < literal.length(); i++) {
                if ((body.get(pos + i) & 0xFF) != literal.charAt(i)) {
                    return false;
                }
            }
            return true;
        }

        private void expect(char expected) {
            skipWs();
            if (!consume(expected)) {
                throw error("expected '" + expected + "'");
            }
        }

        private boolean consume(char expected) {
            if (pos < limit && (body.get(pos) & 0xFF) == expected) {
                pos++;
                return true;
            }
            return false;
        }

        private int peek() {
            if (pos >= limit) {
                throw error("unexpected end");
            }
            return body.get(pos) & 0xFF;
        }

        private int readByte() {
            if (pos >= limit) {
                throw error("unexpected end");
            }
            return body.get(pos++) & 0xFF;
        }

        private void skipWs() {
            while (pos < limit) {
                int ch = body.get(pos) & 0xFF;
                if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t') {
                    pos++;
                    continue;
                }
                break;
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at byte " + pos);
        }
    }
}
