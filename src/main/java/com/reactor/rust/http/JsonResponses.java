package com.reactor.rust.http;

import com.reactor.rust.json.DslJsonService;

/**
 * Small response helpers for handlers that need JSON without hand-written escaping code.
 */
public final class JsonResponses {

    private JsonResponses() {}

    public static RawResponse body(Object value) {
        return RawResponse.json(DslJsonService.serialize(value));
    }

    public static RawResponse error(String code, String message) {
        StringBuilder json = new StringBuilder(length(code) + length(message) + 32);
        json.append("{\"code\":");
        appendString(json, code);
        json.append(",\"message\":");
        appendString(json, message);
        json.append('}');
        return RawResponse.text(json.toString(), MediaType.APPLICATION_JSON_UTF8);
    }

    public static RawResponse stringField(String name, String value) {
        StringBuilder json = new StringBuilder(length(name) + length(value) + 16);
        json.append('{');
        appendString(json, name);
        json.append(':');
        appendString(json, value);
        json.append('}');
        return RawResponse.text(json.toString(), MediaType.APPLICATION_JSON_UTF8);
    }

    public static RawResponse longField(String name, long value) {
        StringBuilder json = new StringBuilder(length(name) + 24);
        json.append('{');
        appendString(json, name);
        json.append(':').append(value).append('}');
        return RawResponse.text(json.toString(), MediaType.APPLICATION_JSON_UTF8);
    }

    public static RawResponse booleanField(String name, boolean value) {
        StringBuilder json = new StringBuilder(length(name) + 16);
        json.append('{');
        appendString(json, name);
        json.append(':').append(value).append('}');
        return RawResponse.text(json.toString(), MediaType.APPLICATION_JSON_UTF8);
    }

    private static void appendString(StringBuilder target, String value) {
        target.append('"');
        if (value != null) {
            for (int index = 0; index < value.length(); index++) {
                char current = value.charAt(index);
                switch (current) {
                    case '"' -> target.append("\\\"");
                    case '\\' -> target.append("\\\\");
                    case '\b' -> target.append("\\b");
                    case '\f' -> target.append("\\f");
                    case '\n' -> target.append("\\n");
                    case '\r' -> target.append("\\r");
                    case '\t' -> target.append("\\t");
                    default -> {
                        if (current < 0x20) {
                            target.append("\\u00")
                                    .append(Character.forDigit((current >>> 4) & 0xF, 16))
                                    .append(Character.forDigit(current & 0xF, 16));
                        } else {
                            target.append(current);
                        }
                    }
                }
            }
        }
        target.append('"');
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }
}
