package com.reactor.rust.bridge;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Startup registry populated by generated application factories. */
public final class GeneratedRouteInvokers {

    private static final ConcurrentHashMap<MethodKey, GeneratedRouteInvoker> INVOKERS =
            new ConcurrentHashMap<>(64);

    private GeneratedRouteInvokers() {}

    public static void register(
            Class<?> owner,
            String methodName,
            Class<?>[] parameterTypes,
            GeneratedRouteInvoker invoker) {
        MethodKey key = new MethodKey(owner, methodName, parameterTypes);
        GeneratedRouteInvoker previous = INVOKERS.putIfAbsent(key, Objects.requireNonNull(invoker, "invoker"));
        if (previous != null && previous.getClass() != invoker.getClass()) {
            throw new IllegalStateException("Generated route invoker already registered: " + key);
        }
    }

    static GeneratedRouteInvoker find(Method method) {
        return INVOKERS.get(new MethodKey(
                method.getDeclaringClass(),
                method.getName(),
                method.getParameterTypes()));
    }

    /** Returns only build-time known route methods for the owner. */
    static Method[] routeMethods(Class<?> owner) {
        ArrayList<Method> methods = new ArrayList<>();
        for (MethodKey key : INVOKERS.keySet()) {
            if (key.owner != owner) continue;
            try {
                methods.add(owner.getDeclaredMethod(key.methodName, key.parameterTypes));
            } catch (NoSuchMethodException failure) {
                throw new IllegalStateException("Generated route method no longer exists: " + key, failure);
            }
        }
        return methods.toArray(Method[]::new);
    }

    public static int size() {
        return INVOKERS.size();
    }

    static void clear() {
        INVOKERS.clear();
    }

    private static final class MethodKey {
        private final Class<?> owner;
        private final String methodName;
        private final Class<?>[] parameterTypes;
        private final int hash;

        private MethodKey(Class<?> owner, String methodName, Class<?>[] parameterTypes) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.methodName = Objects.requireNonNull(methodName, "methodName");
            this.parameterTypes = parameterTypes == null ? new Class<?>[0] : parameterTypes.clone();
            this.hash = 31 * (31 * owner.hashCode() + methodName.hashCode())
                    + Arrays.hashCode(this.parameterTypes);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof MethodKey key
                    && owner == key.owner
                    && methodName.equals(key.methodName)
                    && Arrays.equals(parameterTypes, key.parameterTypes);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public String toString() {
            return owner.getName() + '#' + methodName + Arrays.toString(parameterTypes);
        }
    }
}
