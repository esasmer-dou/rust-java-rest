package com.reactor.rust.config;

import com.reactor.rust.annotations.RustProperty;

import java.lang.reflect.Field;

/**
 * @RustProperty annotation ile isaretlenmis field'lara deger inject eder.
 * Constraint #8: Properties-based configuration
 */
public final class PropertyInjector {

    private PropertyInjector() {}

    /**
     * Bean'in tum @RustProperty field'larina deger inject eder.
     *
     * @param bean Inject edilecek bean
     */
    public static void inject(Object bean) {
        if (bean == null) {
            return;
        }

        Class<?> clazz = bean.getClass();

        // Walk up the class hierarchy
        while (clazz != null && clazz != Object.class) {
            injectFields(bean, clazz);
            clazz = clazz.getSuperclass();
        }
    }

    private static void injectFields(Object bean, Class<?> clazz) {
        for (Field field : clazz.getDeclaredFields()) {
            RustProperty annotation = field.getAnnotation(RustProperty.class);
            if (annotation == null) {
                continue;
            }

            String key = annotation.value();
            String defaultValue = annotation.defaultValue();
            Object value = resolveValue(key, defaultValue, field.getType());

            if (value != null) {
                try {
                    field.setAccessible(true);
                    field.set(bean, value);
                    System.out.println("[PropertyInjector] Injected " + key + " = " + value + " into " + clazz.getSimpleName());
                } catch (IllegalAccessException e) {
                    System.err.println("[PropertyInjector] Failed to inject " + key + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * Properties dosyasindan degeri okur ve tip donusumu yapar.
     */
    private static Object resolveValue(String key, String defaultValue, Class<?> targetType) {
        String stringValue = PropertiesLoader.get(key, defaultValue);

        if (stringValue == null || stringValue.isEmpty()) {
            return null;
        }

        return convertType(stringValue, targetType);
    }

    /**
     * String degeri hedef tipe donusturur.
     */
    private static Object convertType(String value, Class<?> targetType) {
        if (targetType == String.class) {
            return value;
        }

        if (targetType == int.class || targetType == Integer.class) {
            return Integer.parseInt(value);
        }

        if (targetType == long.class || targetType == Long.class) {
            return Long.parseLong(value);
        }

        if (targetType == double.class || targetType == Double.class) {
            return Double.parseDouble(value);
        }

        if (targetType == float.class || targetType == Float.class) {
            return Float.parseFloat(value);
        }

        if (targetType == boolean.class || targetType == Boolean.class) {
            return Boolean.parseBoolean(value);
        }

        if (targetType == short.class || targetType == Short.class) {
            return Short.parseShort(value);
        }

        if (targetType == byte.class || targetType == Byte.class) {
            return Byte.parseByte(value);
        }

        if (targetType == char.class || targetType == Character.class) {
            return value.charAt(0);
        }

        // Enum support
        if (targetType.isEnum()) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object[] enumConstants = targetType.getEnumConstants();
            String upperValue = value.toUpperCase();
            for (Object constant : enumConstants) {
                if (((Enum) constant).name().equals(upperValue)) {
                    return constant;
                }
            }
            return null;
        }

        return value;
    }
}
