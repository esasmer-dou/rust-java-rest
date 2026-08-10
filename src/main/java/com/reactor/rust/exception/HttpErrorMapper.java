package com.reactor.rust.exception;

import com.reactor.rust.config.PropertiesLoader;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Maps framework and application failures to a stable HTTP error contract.
 */
public final class HttpErrorMapper {

    private static final String INTERNAL_ERROR_MESSAGE = "Internal server error";
    private static final byte[] JSON_HEADER =
            "Content-Type: application/json; charset=utf-8\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PROBLEM_JSON_HEADER =
            "Content-Type: application/problem+json; charset=utf-8\n".getBytes(StandardCharsets.UTF_8);

    private HttpErrorMapper() {
    }

    public static MappedError map(Throwable error) {
        Throwable root = unwrap(error);

        if (root instanceof BadRequestException
                || root instanceof ValidationException
                || root instanceof NumberFormatException) {
            return mapped(400, "bad_request", publicMessage(root, "Invalid request"));
        }
        if (root instanceof NotFoundException) {
            return mapped(404, "not_found", publicMessage(root, "Resource not found"));
        }
        if (root instanceof UnauthorizedException) {
            return mapped(401, "unauthorized", publicMessage(root, "Authentication required"));
        }
        if (root instanceof ForbiddenException) {
            return mapped(403, "forbidden", publicMessage(root, "Access denied"));
        }
        if (root instanceof MethodNotAllowedException) {
            return mapped(405, "method_not_allowed", publicMessage(root, "Method not allowed"));
        }
        if (root instanceof RejectedExecutionException) {
            return mapped(503, "service_unavailable", "Service is temporarily busy");
        }
        if (root instanceof TimeoutException) {
            return mapped(504, "gateway_timeout", "Request timed out");
        }

        boolean includeInternalMessage = PropertiesLoader.getBoolean(
                "reactor.rust.errors.include-internal-message",
                false
        );
        return mapped(
                500,
                "internal_server_error",
                includeInternalMessage ? publicMessage(root, INTERNAL_ERROR_MESSAGE) : INTERNAL_ERROR_MESSAGE
        );
    }

    public static byte[] toJsonBytes(MappedError error) {
        if (problemDetailsEnabled()) {
            String title = switch (error.status()) {
                case 400 -> "Bad Request";
                case 404 -> "Not Found";
                case 401 -> "Unauthorized";
                case 403 -> "Forbidden";
                case 405 -> "Method Not Allowed";
                case 503 -> "Service Unavailable";
                case 504 -> "Gateway Timeout";
                default -> "Internal Server Error";
            };
            StringBuilder json = new StringBuilder(error.message().length() + error.code().length() + 96);
            json.append("{\"type\":\"about:blank\",\"title\":\"");
            appendJsonString(json, title);
            json.append("\",\"status\":").append(error.status()).append(",\"detail\":\"");
            appendJsonString(json, error.message());
            json.append("\",\"code\":\"");
            appendJsonString(json, error.code());
            json.append("\"}");
            return json.toString().getBytes(StandardCharsets.UTF_8);
        }
        StringBuilder json = new StringBuilder(error.message().length() + error.code().length() + 32);
        json.append("{\"error\":\"");
        appendJsonString(json, error.message());
        json.append("\",\"code\":\"");
        appendJsonString(json, error.code());
        json.append("\"}");
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] contentTypeHeader() {
        return problemDetailsEnabled() ? PROBLEM_JSON_HEADER : JSON_HEADER;
    }

    private static boolean problemDetailsEnabled() {
        return "problem-details".equalsIgnoreCase(
                PropertiesLoader.get("reactor.rust.errors.format", "problem-details"));
    }

    public static Throwable unwrap(Throwable error) {
        Throwable current = error == null ? new IllegalStateException("Unknown framework error") : error;
        while ((current instanceof CompletionException
                || current instanceof ExecutionException
                || current instanceof InvocationTargetException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static MappedError mapped(int status, String code, String message) {
        return new MappedError(status, code, limit(message));
    }

    private static String publicMessage(Throwable error, String fallback) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    private static String limit(String message) {
        int maxChars = Math.max(
                64,
                PropertiesLoader.getInt("reactor.rust.errors.max-message-chars", 512)
        );
        if (message.length() <= maxChars) {
            return message;
        }
        return message.substring(0, maxChars);
    }

    private static void appendJsonString(StringBuilder target, String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> target.append("\\\"");
                case '\\' -> target.append("\\\\");
                case '\b' -> target.append("\\b");
                case '\f' -> target.append("\\f");
                case '\n' -> target.append("\\n");
                case '\r' -> target.append("\\r");
                case '\t' -> target.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        target.append("\\u00");
                        target.append(Character.forDigit((ch >>> 4) & 0xF, 16));
                        target.append(Character.forDigit(ch & 0xF, 16));
                    } else {
                        target.append(ch);
                    }
                }
            }
        }
    }

    public record MappedError(int status, String code, String message) {
    }
}
