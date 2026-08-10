package com.reactor.rust.validation;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.regex.Pattern;

/** Allocation-aware helpers used by generated validators. */
public final class GeneratedValidationSupport {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,7}$");

    private GeneratedValidationSupport() {}

    public static ArrayList<ConstraintViolation> add(
            ArrayList<ConstraintViolation> violations,
            String field,
            String message,
            Object invalidValue) {
        ArrayList<ConstraintViolation> result = violations;
        if (result == null) {
            result = new ArrayList<>(2);
        }
        result.add(new ConstraintViolation(field, message, invalidValue));
        return result;
    }

    public static ValidationResult result(ArrayList<ConstraintViolation> violations) {
        return violations == null ? ValidationResult.success() : ValidationResult.of(violations);
    }

    public static boolean isBlank(Object value) {
        return !(value instanceof String text) || text.trim().isEmpty();
    }

    public static boolean isBlankString(Object value) {
        return value instanceof String text && text.isBlank();
    }

    public static boolean isEmpty(Object value) {
        if (value instanceof String text) return text.isEmpty();
        if (value instanceof Collection<?> collection) return collection.isEmpty();
        if (value instanceof Map<?, ?> map) return map.isEmpty();
        return value != null && value.getClass().isArray() && Array.getLength(value) == 0;
    }

    public static int length(Object value) {
        if (value instanceof String text) return text.length();
        if (value instanceof Collection<?> collection) return collection.size();
        if (value instanceof Map<?, ?> map) return map.size();
        return value != null && value.getClass().isArray() ? Array.getLength(value) : 0;
    }

    public static boolean isEmail(Object value) {
        return value instanceof String text && EMAIL_PATTERN.matcher(text).matches();
    }

    public static boolean invalidEmailIfString(Object value) {
        return value instanceof String text && !EMAIL_PATTERN.matcher(text).matches();
    }

    public static boolean matches(Pattern pattern, Object value) {
        return value instanceof String text && pattern.matcher(text).matches();
    }

    public static boolean mismatchesIfString(Pattern pattern, Object value) {
        return value instanceof String text && !pattern.matcher(text).matches();
    }

    public static boolean isNumber(Object value) {
        return value instanceof Number;
    }

    public static double number(Object value) {
        return ((Number) value).doubleValue();
    }
}
