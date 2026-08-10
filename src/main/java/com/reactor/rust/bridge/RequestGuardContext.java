package com.reactor.rust.bridge;

/** Lazy raw request view allocated only for guarded routes. */
public final class RequestGuardContext {
    private final String pathParameters;
    private final String query;
    private final String headers;
    private final byte[] body;

    public RequestGuardContext(String pathParameters, String query, String headers, byte[] body) {
        this.pathParameters = pathParameters;
        this.query = query;
        this.headers = headers;
        this.body = body;
    }

    public String pathParameters() { return pathParameters; }
    public String query() { return query; }
    public byte[] body() { return body; }

    public String header(String name) {
        if (headers == null || headers.isEmpty() || name == null || name.isEmpty()) return null;
        int start = 0;
        int length = headers.length();
        while (start < length) {
            int end = headers.indexOf('\n', start);
            if (end < 0) end = length;
            int separator = headers.indexOf(':', start);
            if (separator > start && separator < end) {
                int keyStart = start;
                while (keyStart < separator && Character.isWhitespace(headers.charAt(keyStart))) keyStart++;
                int keyEnd = separator;
                while (keyEnd > keyStart && Character.isWhitespace(headers.charAt(keyEnd - 1))) keyEnd--;
                if (keyEnd - keyStart == name.length()
                        && headers.regionMatches(true, keyStart, name, 0, name.length())) {
                    int valueStart = separator + 1;
                    while (valueStart < end && Character.isWhitespace(headers.charAt(valueStart))) valueStart++;
                    int valueEnd = end;
                    while (valueEnd > valueStart && Character.isWhitespace(headers.charAt(valueEnd - 1))) valueEnd--;
                    return headers.substring(valueStart, valueEnd);
                }
            }
            start = end + 1;
        }
        return null;
    }
}
