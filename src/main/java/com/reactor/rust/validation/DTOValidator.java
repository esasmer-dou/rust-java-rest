package com.reactor.rust.validation;

import com.reactor.rust.annotations.Field;
import com.reactor.rust.annotations.Request;
import com.reactor.rust.annotations.Response;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

/**
 * @Request ve @Response annotation'lari ile isaretlenmis Record'lar icin validation.
 * @Field annotation'i ile field bazli kurallar uygulanir.
 *
 * Constraint #9: Record Annotation'lari - REST API Ready
 */
public final class DTOValidator {

    private static final DTOValidator INSTANCE = new DTOValidator();

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
     * @Field annotation'larini kontrol eder.
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
                Field fieldAnnotation = component.getAnnotation(Field.class);

                if (fieldAnnotation != null) {
                    validateField(component.getName(), value, fieldAnnotation, violations);
                }
            }

        } catch (Exception e) {
            violations.add(new ConstraintViolation("object", "validation error: " + e.getMessage(), null));
        }

        return ValidationResult.of(violations);
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
     * Tek bir field'i @Field annotation kurallarina gore validate eder.
     */
    private void validateField(String fieldName, Object value, Field annotation, List<ConstraintViolation> violations) {

        // Required kontrolu
        if (annotation.required() && value == null) {
            violations.add(new ConstraintViolation(fieldName, "is required", value));
            return;
        }

        // Null ise diger kontrolleri atla
        if (value == null) {
            return;
        }

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
     * Default value'u uygula (eger null ise ve defaultValue varsa).
     * NOT: Record'lar immutable oldugu icin, bu metod yeni bir record dondurmez.
     * Sadece validation oncesi kontrol icin kullanilir.
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
