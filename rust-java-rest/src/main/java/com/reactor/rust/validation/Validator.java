package com.reactor.rust.validation;

import com.reactor.rust.annotations.*;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Validates objects using validation annotations.
 * Zero-overhead implementation using reflection only on validation path.
 */
public final class Validator {

    private static final Validator INSTANCE = new Validator();

    // Email regex pattern - simple but effective
    private static final String EMAIL_REGEX = "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,7}$";

    private Validator() {}

    public static Validator getInstance() {
        return INSTANCE;
    }

    /**
     * Validate an object using its field annotations.
     */
    public ValidationResult validate(Object obj) {
        if (obj == null) {
            return ValidationResult.failure("object", "must not be null", null);
        }

        List<ConstraintViolation> violations = new ArrayList<>();
        Class<?> clazz = obj.getClass();

        // Get all declared fields including from parent classes
        Class<?> currentClass = clazz;
        while (currentClass != null && currentClass != Object.class) {
            validateFields(obj, currentClass, violations);
            currentClass = currentClass.getSuperclass();
        }

        return ValidationResult.of(violations);
    }

    private void validateFields(Object obj, Class<?> clazz, List<ConstraintViolation> violations) {
        for (Field field : clazz.getDeclaredFields()) {
            field.setAccessible(true);
            Object value;
            try {
                value = field.get(obj);
            } catch (IllegalAccessException e) {
                continue;
            }

            String fieldName = field.getName();

            // @NotNull
            NotNull notNull = field.getAnnotation(NotNull.class);
            if (notNull != null && value == null) {
                violations.add(new ConstraintViolation(fieldName, notNull.message(), value));
                continue;  // Skip other validations if null
            }

            // Skip remaining validations if value is null
            if (value == null) {
                continue;
            }

            // @NotBlank
            NotBlank notBlank = field.getAnnotation(NotBlank.class);
            if (notBlank != null) {
                if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
                    violations.add(new ConstraintViolation(fieldName, notBlank.message(), value));
                }
            }

            // @NotEmpty
            NotEmpty notEmpty = field.getAnnotation(NotEmpty.class);
            if (notEmpty != null) {
                if (value instanceof String && ((String) value).isEmpty()) {
                    violations.add(new ConstraintViolation(fieldName, notEmpty.message(), value));
                } else if (value instanceof Collection && ((Collection<?>) value).isEmpty()) {
                    violations.add(new ConstraintViolation(fieldName, notEmpty.message(), value));
                } else if (value instanceof Map && ((Map<?, ?>) value).isEmpty()) {
                    violations.add(new ConstraintViolation(fieldName, notEmpty.message(), value));
                } else if (value.getClass().isArray() && java.lang.reflect.Array.getLength(value) == 0) {
                    violations.add(new ConstraintViolation(fieldName, notEmpty.message(), value));
                }
            }

            // @Size
            Size size = field.getAnnotation(Size.class);
            if (size != null) {
                int length = getLength(value);
                if (length < size.min() || length > size.max()) {
                    String msg = size.message()
                            .replace("{min}", String.valueOf(size.min()))
                            .replace("{max}", String.valueOf(size.max()));
                    violations.add(new ConstraintViolation(fieldName, msg, value));
                }
            }

            // @Min
            Min minAnno = field.getAnnotation(Min.class);
            if (minAnno != null && value instanceof Number) {
                long num = ((Number) value).longValue();
                if (num < minAnno.value()) {
                    String msg = minAnno.message().replace("{value}", String.valueOf(minAnno.value()));
                    violations.add(new ConstraintViolation(fieldName, msg, value));
                }
            }

            // @Max
            Max maxAnno = field.getAnnotation(Max.class);
            if (maxAnno != null && value instanceof Number) {
                long num = ((Number) value).longValue();
                if (num > maxAnno.value()) {
                    String msg = maxAnno.message().replace("{value}", String.valueOf(maxAnno.value()));
                    violations.add(new ConstraintViolation(fieldName, msg, value));
                }
            }

            // @Positive
            Positive positive = field.getAnnotation(Positive.class);
            if (positive != null && value instanceof Number) {
                double num = ((Number) value).doubleValue();
                if (num <= 0) {
                    violations.add(new ConstraintViolation(fieldName, positive.message(), value));
                }
            }

            // @Negative
            Negative negative = field.getAnnotation(Negative.class);
            if (negative != null && value instanceof Number) {
                double num = ((Number) value).doubleValue();
                if (num >= 0) {
                    violations.add(new ConstraintViolation(fieldName, negative.message(), value));
                }
            }

            // @DecimalMin
            DecimalMin decimalMin = field.getAnnotation(DecimalMin.class);
            if (decimalMin != null && value instanceof Number) {
                BigDecimal min = new BigDecimal(decimalMin.value());
                BigDecimal num = new BigDecimal(value.toString());
                if (num.compareTo(min) < 0) {
                    String msg = decimalMin.message().replace("{value}", decimalMin.value());
                    violations.add(new ConstraintViolation(fieldName, msg, value));
                }
            }

            // @DecimalMax
            DecimalMax decimalMax = field.getAnnotation(DecimalMax.class);
            if (decimalMax != null && value instanceof Number) {
                BigDecimal max = new BigDecimal(decimalMax.value());
                BigDecimal num = new BigDecimal(value.toString());
                if (num.compareTo(max) > 0) {
                    String msg = decimalMax.message().replace("{value}", decimalMax.value());
                    violations.add(new ConstraintViolation(fieldName, msg, value));
                }
            }

            // @Pattern
            Pattern pattern = field.getAnnotation(Pattern.class);
            if (pattern != null && value instanceof String) {
                if (!((String) value).matches(pattern.regexp())) {
                    String msg = pattern.message().replace("{regexp}", pattern.regexp());
                    violations.add(new ConstraintViolation(fieldName, msg, value));
                }
            }

            // @Email
            Email email = field.getAnnotation(Email.class);
            if (email != null && value instanceof String) {
                if (!((String) value).matches(EMAIL_REGEX)) {
                    violations.add(new ConstraintViolation(fieldName, email.message(), value));
                }
            }
        }
    }

    private int getLength(Object value) {
        if (value instanceof String) {
            return ((String) value).length();
        } else if (value instanceof Collection) {
            return ((Collection<?>) value).size();
        } else if (value instanceof Map) {
            return ((Map<?, ?>) value).size();
        } else if (value.getClass().isArray()) {
            return java.lang.reflect.Array.getLength(value);
        }
        return 0;
    }
}
