package com.reactor.rust.validation;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Registry populated by the generated application descriptor during startup. */
public final class GeneratedValidators {

    private static final Entry MISSING = new Entry(null, Map.of());
    private static final ConcurrentHashMap<Class<?>, Entry> REGISTERED = new ConcurrentHashMap<>();
    private static final ClassValue<Entry> CACHE = new ClassValue<>() {
        @Override
        protected Entry computeValue(Class<?> type) {
            return REGISTERED.getOrDefault(type, MISSING);
        }
    };

    private GeneratedValidators() {}

    public static void register(
            Class<?> type,
            GeneratedValidator validator,
            Map<String, String> defaultValues) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(validator, "validator");
        Entry entry = new Entry(validator, defaultValues == null ? Map.of() : Map.copyOf(defaultValues));
        REGISTERED.put(type, entry);
        CACHE.remove(type);
    }

    static ValidationResult validateOrNull(Object value) {
        Entry entry = CACHE.get(value.getClass());
        return entry.validator() == null ? null : entry.validator().validate(value);
    }

    static boolean isRegistered(Class<?> type) {
        return CACHE.get(type).validator() != null;
    }

    static boolean hasDefaultValue(Class<?> type, String fieldName) {
        return CACHE.get(type).defaultValues().containsKey(fieldName);
    }

    static String defaultValue(Class<?> type, String fieldName) {
        return CACHE.get(type).defaultValues().get(fieldName);
    }

    private record Entry(GeneratedValidator validator, Map<String, String> defaultValues) {}
}
