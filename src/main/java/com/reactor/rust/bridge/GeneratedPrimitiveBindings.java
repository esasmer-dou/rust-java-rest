package com.reactor.rust.bridge;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Startup registry populated by generated component factories. */
public final class GeneratedPrimitiveBindings {

    private static final Map<RouteKey, GeneratedPrimitiveBinding> BINDINGS = new ConcurrentHashMap<>();

    private GeneratedPrimitiveBindings() {}

    public static void register(
            Class<?> owner,
            String methodName,
            Class<?>[] parameterTypes,
            GeneratedPrimitiveBinding binding) {
        BINDINGS.put(new RouteKey(owner, methodName, parameterTypes), binding);
    }

    public static GeneratedPrimitiveBinding find(Method method) {
        return BINDINGS.get(new RouteKey(
                method.getDeclaringClass(), method.getName(), method.getParameterTypes()));
    }

    public static int size() {
        return BINDINGS.size();
    }

    public static void releaseStartupMetadata() {
        BINDINGS.clear();
    }

    private record RouteKey(Class<?> owner, String methodName, Class<?>[] parameterTypes) {
        private RouteKey {
            parameterTypes = parameterTypes.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof RouteKey key
                    && owner == key.owner
                    && methodName.equals(key.methodName)
                    && Arrays.equals(parameterTypes, key.parameterTypes);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * owner.hashCode() + methodName.hashCode())
                    + Arrays.hashCode(parameterTypes);
        }
    }
}
