package com.reactor.rust.validation;

import com.reactor.rust.annotations.*;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * @Request ve @Response annotation'lari ile isaretlenmis Record'lar icin validation.
 * Tum validation annotation'larini destekler.
 *
 * Supported annotations:
 * - @Field (required, min, max, pattern, defaultValue)
 * - @NotNull, @NotBlank, @NotEmpty
 * - @Size (min, max)
 * - @Min, @Max
 * - @Positive, @Negative
 * - @DecimalMin, @DecimalMax
 * - @Pattern
 * - @Email
 */
public final class DTOValidator {

    private static final DTOValidator INSTANCE = new DTOValidator();

    // Email regex - RFC 5322 compliant simplified
    private static final java.util.regex.Pattern EMAIL_PATTERN = java.util.regex.Pattern.compile(
        "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,7}$"
    );

    private DTOValidator() {}

    public static DTOValidator getInstance() {
        return INSTANCE;
    }

    /**
     * Object'in @Request veya @Response ile isaretlenmis olup olmadigini kontrol eder.
     */
    public boolean isDTO(Class<?> clazz) {
        return clazz.isAnnotationPresent(Request.class) || clazz.isAnnotationPresent(Response.class);
    }

    /**
     * @Request/@Response ile isaretlenmis Record'u validate eder.
     * Tum validation annotation'larini kontrol eder.
     */
    public ValidationResult validate(Object obj) {
        if (obj == null) {
            return ValidationResult.failure("object", "must not be null", null);
        }

        Class<?> clazz = obj.getClass();

        // Sadece @Request/@Response ile isaretli class'lari validate et
        if (!isDTO(clazz)) {
            return ValidationResult.success();
        }

        // Sadece Record'lari destekle (Constraint #7)
        if (!clazz.isRecord()) {
            return ValidationResult.failure("class", "must be a Record", clazz.getName());
        }

        List<ConstraintViolation> violations = new ArrayList<>();
        RecordComponent[] components = clazz.getRecordComponents();

        try {
            Object[] componentValues = extractRecordValues(obj, components);

            for (int i = 0; i < components.length; i++) {
                RecordComponent component = components[i];
                Object value = componentValues[i];
                String fieldName = component.getName();

                // Validate all annotations
                validateFieldAnnotations(fieldName, value, component, violations);
            }

        } catch (Exception e) {
            violations.add(new ConstraintViolation("object", "validation error: " + e.getMessage(), null));
        }

        return ValidationResult.of(violations);
    }

    /**
     * Validate all annotation types for a field.
     */
    private void validateFieldAnnotations(String fieldName, Object value, RecordComponent component,
                                          List<ConstraintViolation> violations) {

        // @NotNull
        NotNull notNull = component.getAnnotation(NotNull.class);
        if (notNull != null && value == null) {
            violations.add(new ConstraintViolation(fieldName, notNull.message(), value));
            return;
        }

        // @Field.required
        Field fieldAnnotation = component.getAnnotation(Field.class);
        if (fieldAnnotation != null && fieldAnnotation.required() && value == null) {
            violations.add(new ConstraintViolation(fieldName, "is required", value));
            return;
        }

        // Skip remaining validations if null
        if (value == null) {
            return;
        }

        // @NotBlank
        NotBlank notBlank = component.getAnnotation(NotBlank.class);
        if (notBlank != null) {
            if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
                violations.add(new ConstraintViolation(fieldName, notBlank.message(), value));
            }
        }

        // @NotEmpty
        NotEmpty notEmpty = component.getAnnotation(NotEmpty.class);
        if (notEmpty != null) {
            if (isEmpty(value)) {
                violations.add(new ConstraintViolation(fieldName, notEmpty.message(), value));
            }
        }

        // @Size
        Size size = component.getAnnotation(Size.class);
        if (size != null) {
            int length = getLength(value);
            if (length < size.min() || length > size.max()) {
                String msg = size.message()
                    .replace("{min}", String.valueOf(size.min()))
                    .replace("{max}", String.valueOf(size.max()));
                violations.add(new ConstraintViolation(fieldName, msg, value));
            }
        }

        // @Email
        Email email = component.getAnnotation(Email.class);
        if (email != null && value instanceof String) {
            if (!EMAIL_PATTERN.matcher((String) value).matches()) {
                violations.add(new ConstraintViolation(fieldName, email.message(), value));
            }
        }

        // @Pattern
        Pattern pattern = component.getAnnotation(Pattern.class);
        if (pattern != null && value instanceof String) {
            if (!((String) value).matches(pattern.regexp())) {
                String msg = pattern.message().replace("{regexp}", pattern.regexp());
                violations.add(new ConstraintViolation(fieldName, msg, value));
            }
        }

        // @Min
        Min min = component.getAnnotation(Min.class);
        if (min != null && value instanceof Number) {
            long num = ((Number) value).longValue();
            if (num < min.value()) {
                String msg = min.message().replace("{value}", String.valueOf(min.value()));
                violations.add(new ConstraintViolation(fieldName, msg, value));
            }
        }

        // @Max
        Max max = component.getAnnotation(Max.class);
        if (max != null && value instanceof Number) {
            long num = ((Number) value).longValue();
            if (num > max.value()) {
                String msg = max.message().replace("{value}", String.valueOf(max.value()));
                violations.add(new ConstraintViolation(fieldName, msg, value));
            }
        }

        // @Positive
        Positive positive = component.getAnnotation(Positive.class);
        if (positive != null && value instanceof Number) {
            if (((Number) value).doubleValue() <= 0) {
                violations.add(new ConstraintViolation(fieldName, positive.message(), value));
            }
        }

        // @Negative
        Negative negative = component.getAnnotation(Negative.class);
        if (negative != null && value instanceof Number) {
            if (((Number) value).doubleValue() >= 0) {
                violations.add(new ConstraintViolation(fieldName, negative.message(), value));
            }
        }

        // @DecimalMin
        DecimalMin decimalMin = component.getAnnotation(DecimalMin.class);
        if (decimalMin != null && value instanceof Number) {
            double num = ((Number) value).doubleValue();
            double minVal = Double.parseDouble(decimalMin.value());
            if (num < minVal) {
                violations.add(new ConstraintViolation(fieldName, decimalMin.message(), value));
            }
        }

        // @DecimalMax
        DecimalMax decimalMax = component.getAnnotation(DecimalMax.class);
        if (decimalMax != null && value instanceof Number) {
            double num = ((Number) value).doubleValue();
            double maxVal = Double.parseDouble(decimalMax.value());
            if (num > maxVal) {
                violations.add(new ConstraintViolation(fieldName, decimalMax.message(), value));
            }
        }

        // @Field annotation validation (min/max/pattern)
        if (fieldAnnotation != null) {
            validateFieldAnnotation(fieldName, value, fieldAnnotation, violations);
        }
    }

    /**
     * @Field annotation validation.
     */
    private void validateFieldAnnotation(String fieldName, Object value, Field annotation,
                                         List<ConstraintViolation> violations) {
        // String kontrolleri
        if (value instanceof String strValue) {
            // Required (blank kontrolu)
            if (annotation.required() && strValue.isBlank()) {
                violations.add(new ConstraintViolation(fieldName, "is required and cannot be blank", value));
            }

            // Pattern
            String pattern = annotation.pattern();
            if (!pattern.isEmpty() && !strValue.matches(pattern)) {
                violations.add(new ConstraintViolation(fieldName, "does not match pattern: " + pattern, value));
            }
        }

        // Sayisal kontroller
        if (value instanceof Number numValue) {
            double num = numValue.doubleValue();
            double min = annotation.min();
            double max = annotation.max();

            // Min kontrolu (Double.MIN_VALUE default, kontrol etme)
            if (min != Double.MIN_VALUE && num < min) {
                violations.add(new ConstraintViolation(fieldName, "must be >= " + min, value));
            }

            // Max kontrolu (Double.MAX_VALUE default, kontrol etme)
            if (max != Double.MAX_VALUE && num > max) {
                violations.add(new ConstraintViolation(fieldName, "must be <= " + max, value));
            }
        }
    }

    /**
     * Check if value is empty (String, Collection, Map, Array).
     */
    private boolean isEmpty(Object value) {
        if (value instanceof String) {
            return ((String) value).isEmpty();
        } else if (value instanceof Collection) {
            return ((Collection<?>) value).isEmpty();
        } else if (value instanceof Map) {
            return ((Map<?, ?>) value).isEmpty();
        } else if (value.getClass().isArray()) {
            return java.lang.reflect.Array.getLength(value) == 0;
        }
        return false;
    }

    /**
     * Get length of value (String, Collection, Map, Array).
     */
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

    /**
     * Record degerlerini extract eder.
     */
    private Object[] extractRecordValues(Object record, RecordComponent[] components) throws Exception {
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            values[i] = components[i].getAccessor().invoke(record);
        }
        return values;
    }

    /**
     * Default value'u uygula (eger null ise ve defaultValue varsa).
     */
    public boolean hasDefaultValue(Object obj, String fieldName) {
        if (obj == null || !obj.getClass().isRecord()) {
            return false;
        }

        try {
            RecordComponent[] components = obj.getClass().getRecordComponents();
            for (RecordComponent component : components) {
                if (component.getName().equals(fieldName)) {
                    Field annotation = component.getAnnotation(Field.class);
                    return annotation != null && !annotation.defaultValue().isEmpty();
                }
            }
        } catch (Exception e) {
            // Ignore
        }

        return false;
    }

    /**
     * Field icin default value'i dondur.
     */
    public String getDefaultValue(Class<?> recordClass, String fieldName) {
        if (!recordClass.isRecord()) {
            return null;
        }

        try {
            RecordComponent[] components = recordClass.getRecordComponents();
            for (RecordComponent component : components) {
                if (component.getName().equals(fieldName)) {
                    Field annotation = component.getAnnotation(Field.class);
                    if (annotation != null && !annotation.defaultValue().isEmpty()) {
                        return annotation.defaultValue();
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }

        return null;
    }
}
