package com.reactor.rust.validation;

import com.reactor.rust.annotations.DecimalMax;
import com.reactor.rust.annotations.DecimalMin;
import com.reactor.rust.annotations.Email;
import com.reactor.rust.annotations.Max;
import com.reactor.rust.annotations.Min;
import com.reactor.rust.annotations.Negative;
import com.reactor.rust.annotations.NotBlank;
import com.reactor.rust.annotations.NotEmpty;
import com.reactor.rust.annotations.NotNull;
import com.reactor.rust.annotations.Pattern;
import com.reactor.rust.annotations.Positive;
import com.reactor.rust.annotations.Request;
import com.reactor.rust.annotations.Response;
import com.reactor.rust.annotations.Size;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validates framework request/response records using per-class compiled plans. */
public final class DTOValidator {

    private static final DTOValidator INSTANCE = new DTOValidator();
    private static final java.util.regex.Pattern EMAIL_PATTERN = java.util.regex.Pattern.compile(
            "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,7}$");
    private static final ClassValue<ValidationPlan> PLANS = new ClassValue<>() {
        @Override
        protected ValidationPlan computeValue(Class<?> type) {
            return ValidationPlan.compile(type);
        }
    };

    private DTOValidator() {
    }

    public static DTOValidator getInstance() {
        return INSTANCE;
    }

    public boolean isDTO(Class<?> clazz) {
        return GeneratedValidators.isRegistered(clazz) || PLANS.get(clazz).dto();
    }

    public ValidationResult validate(Object obj) {
        if (obj == null) {
            return ValidationResult.failure("object", "must not be null", null);
        }

        ValidationResult generated = GeneratedValidators.validateOrNull(obj);
        if (generated != null) {
            return generated;
        }

        ValidationPlan plan = PLANS.get(obj.getClass());
        if (!plan.dto()) {
            return ValidationResult.success();
        }
        if (!plan.record()) {
            return ValidationResult.failure("class", "must be a Record", obj.getClass().getName());
        }

        ViolationCollector violations = new ViolationCollector();
        try {
            for (FieldPlan field : plan.fields()) {
                field.validate(field.accessor().get(obj), violations);
            }
        } catch (Throwable failure) {
            if (failure instanceof Error error) {
                throw error;
            }
            violations.add(
                    "object",
                    "validation error: " + safeMessage(failure),
                    null);
        }
        return violations.result();
    }

    public boolean hasDefaultValue(Object obj, String fieldName) {
        if (obj == null || fieldName == null) {
            return false;
        }
        Class<?> type = obj.getClass();
        return GeneratedValidators.hasDefaultValue(type, fieldName)
                || PLANS.get(type).defaults().containsKey(fieldName);
    }

    public String getDefaultValue(Class<?> recordClass, String fieldName) {
        if (recordClass == null || fieldName == null) {
            return null;
        }
        String generated = GeneratedValidators.defaultValue(recordClass, fieldName);
        return generated != null ? generated : PLANS.get(recordClass).defaults().get(fieldName);
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static boolean isEmpty(Object value) {
        if (value instanceof String string) {
            return string.isEmpty();
        }
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        return value.getClass().isArray() && Array.getLength(value) == 0;
    }

    private static int lengthOf(Object value) {
        if (value instanceof String string) {
            return string.length();
        }
        if (value instanceof Collection<?> collection) {
            return collection.size();
        }
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        return value.getClass().isArray() ? Array.getLength(value) : 0;
    }

    private record ValidationPlan(
            boolean dto,
            boolean record,
            FieldPlan[] fields,
            Map<String, String> defaults) {

        static ValidationPlan compile(Class<?> type) {
            boolean dto = type.isAnnotationPresent(Request.class) || type.isAnnotationPresent(Response.class);
            if (!dto || !type.isRecord()) {
                return new ValidationPlan(dto, type.isRecord(), new FieldPlan[0], Map.of());
            }

            RecordComponent[] components = type.getRecordComponents();
            FieldPlan[] fields = new FieldPlan[components.length];
            Map<String, String> defaults = new LinkedHashMap<>();
            for (int i = 0; i < components.length; i++) {
                RecordComponent component = components[i];
                fields[i] = FieldPlan.compile(type, component);
                String defaultValue = fields[i].defaultValue();
                if (defaultValue != null) {
                    defaults.put(component.getName(), defaultValue);
                }
            }
            return new ValidationPlan(true, true, fields, Map.copyOf(defaults));
        }
    }

    private record FieldPlan(
            String name,
            ValueAccessor accessor,
            String notNullMessage,
            boolean required,
            String notBlankMessage,
            String notEmptyMessage,
            int sizeMin,
            int sizeMax,
            String sizeMessage,
            String emailMessage,
            java.util.regex.Pattern pattern,
            String patternMessage,
            Long min,
            String minMessage,
            Long max,
            String maxMessage,
            String positiveMessage,
            String negativeMessage,
            Double decimalMin,
            String decimalMinMessage,
            Double decimalMax,
            String decimalMaxMessage,
            java.util.regex.Pattern fieldPattern,
            Double fieldMin,
            Double fieldMax,
            String defaultValue) {

        static FieldPlan compile(Class<?> recordType, RecordComponent component) {
            NotNull notNull = annotation(recordType, component, NotNull.class);
            com.reactor.rust.annotations.Field field = annotation(
                    recordType, component, com.reactor.rust.annotations.Field.class);
            NotBlank notBlank = annotation(recordType, component, NotBlank.class);
            NotEmpty notEmpty = annotation(recordType, component, NotEmpty.class);
            Size size = annotation(recordType, component, Size.class);
            Email email = annotation(recordType, component, Email.class);
            Pattern pattern = annotation(recordType, component, Pattern.class);
            Min min = annotation(recordType, component, Min.class);
            Max max = annotation(recordType, component, Max.class);
            Positive positive = annotation(recordType, component, Positive.class);
            Negative negative = annotation(recordType, component, Negative.class);
            DecimalMin decimalMin = annotation(recordType, component, DecimalMin.class);
            DecimalMax decimalMax = annotation(recordType, component, DecimalMax.class);

            String annotationPattern = pattern == null ? null : pattern.regexp();
            String fieldPatternValue = field == null || field.pattern().isEmpty() ? null : field.pattern();
            String defaultValue = field == null || field.defaultValue().isEmpty() ? null : field.defaultValue();

            return new FieldPlan(
                    component.getName(),
                    DTOValidator.accessor(recordType, component.getAccessor()),
                    notNull == null ? null : notNull.message(),
                    field != null && field.required(),
                    notBlank == null ? null : notBlank.message(),
                    notEmpty == null ? null : notEmpty.message(),
                    size == null ? -1 : size.min(),
                    size == null ? -1 : size.max(),
                    size == null ? null : size.message()
                            .replace("{min}", Integer.toString(size.min()))
                            .replace("{max}", Integer.toString(size.max())),
                    email == null ? null : email.message(),
                    annotationPattern == null ? null : java.util.regex.Pattern.compile(annotationPattern),
                    pattern == null ? null : pattern.message().replace("{regexp}", annotationPattern),
                    min == null ? null : min.value(),
                    min == null ? null : min.message().replace("{value}", Long.toString(min.value())),
                    max == null ? null : max.value(),
                    max == null ? null : max.message().replace("{value}", Long.toString(max.value())),
                    positive == null ? null : positive.message(),
                    negative == null ? null : negative.message(),
                    decimalMin == null ? null : Double.parseDouble(decimalMin.value()),
                    decimalMin == null ? null : decimalMin.message(),
                    decimalMax == null ? null : Double.parseDouble(decimalMax.value()),
                    decimalMax == null ? null : decimalMax.message(),
                    fieldPatternValue == null ? null : java.util.regex.Pattern.compile(fieldPatternValue),
                    field == null || field.min() == Double.MIN_VALUE ? null : field.min(),
                    field == null || field.max() == Double.MAX_VALUE ? null : field.max(),
                    defaultValue);
        }

        void validate(Object value, ViolationCollector violations) {
            if (value == null) {
                if (notNullMessage != null) {
                    violations.add(name, notNullMessage, null);
                } else if (required) {
                    violations.add(name, "is required", null);
                }
                return;
            }

            if (notBlankMessage != null
                    && (!(value instanceof String string) || string.trim().isEmpty())) {
                violations.add(name, notBlankMessage, value);
            }
            if (notEmptyMessage != null && isEmpty(value)) {
                violations.add(name, notEmptyMessage, value);
            }
            if (sizeMessage != null) {
                int length = lengthOf(value);
                if (length < sizeMin || length > sizeMax) {
                    violations.add(name, sizeMessage, value);
                }
            }
            if (emailMessage != null && value instanceof String string
                    && !EMAIL_PATTERN.matcher(string).matches()) {
                violations.add(name, emailMessage, value);
            }
            if (pattern != null && value instanceof String string && !pattern.matcher(string).matches()) {
                violations.add(name, patternMessage, value);
            }
            if (min != null && value instanceof Number number && number.longValue() < min) {
                violations.add(name, minMessage, value);
            }
            if (max != null && value instanceof Number number && number.longValue() > max) {
                violations.add(name, maxMessage, value);
            }
            if (positiveMessage != null && value instanceof Number number && number.doubleValue() <= 0) {
                violations.add(name, positiveMessage, value);
            }
            if (negativeMessage != null && value instanceof Number number && number.doubleValue() >= 0) {
                violations.add(name, negativeMessage, value);
            }
            if (decimalMin != null && value instanceof Number number && number.doubleValue() < decimalMin) {
                violations.add(name, decimalMinMessage, value);
            }
            if (decimalMax != null && value instanceof Number number && number.doubleValue() > decimalMax) {
                violations.add(name, decimalMaxMessage, value);
            }
            if (value instanceof String string) {
                if (required && string.isBlank()) {
                    violations.add(name, "is required and cannot be blank", value);
                }
                if (fieldPattern != null && !fieldPattern.matcher(string).matches()) {
                    violations.add(name, "does not match pattern: " + fieldPattern.pattern(), value);
                }
            }
            if (value instanceof Number number) {
                double numeric = number.doubleValue();
                if (fieldMin != null && numeric < fieldMin) {
                    violations.add(name, "must be >= " + fieldMin, value);
                }
                if (fieldMax != null && numeric > fieldMax) {
                    violations.add(name, "must be <= " + fieldMax, value);
                }
            }
        }
    }

    private static ValueAccessor accessor(Class<?> recordType, Method method) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(recordType, MethodHandles.lookup());
            MethodHandle handle = lookup.unreflect(method)
                    .asType(MethodType.methodType(Object.class, Object.class));
            return target -> (Object) handle.invokeExact(target);
        } catch (IllegalAccessException inaccessible) {
            if (!method.trySetAccessible()) {
                throw new IllegalArgumentException(
                        "Record accessor is not accessible: " + method.toGenericString(),
                        inaccessible);
            }
            return target -> {
                try {
                    return method.invoke(target);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            };
        }
    }

    private static <A extends Annotation> A annotation(
            Class<?> recordType,
            RecordComponent component,
            Class<A> annotationType) {
        A direct = component.getAnnotation(annotationType);
        if (direct != null) {
            return direct;
        }
        try {
            Field backingField = recordType.getDeclaredField(component.getName());
            return backingField.getAnnotation(annotationType);
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    @FunctionalInterface
    private interface ValueAccessor {
        Object get(Object target) throws Throwable;
    }

    private static final class ViolationCollector {

        private List<ConstraintViolation> values;

        void add(String field, String message, Object invalidValue) {
            if (values == null) {
                values = new ArrayList<>(2);
            }
            values.add(new ConstraintViolation(field, message, invalidValue));
        }

        ValidationResult result() {
            return values == null ? ValidationResult.success() : ValidationResult.of(values);
        }
    }
}
