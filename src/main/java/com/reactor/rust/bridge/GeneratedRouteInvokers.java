package com.reactor.rust.bridge;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Startup-only registry populated by generated application factories. */
public final class GeneratedRouteInvokers {

    private static final Map<Class<?>, OwnerRoutes> OWNERS = new HashMap<>(32);

    private GeneratedRouteInvokers() {}

    public static synchronized void register(
            Class<?> owner,
            String methodName,
            Class<?>[] parameterTypes,
            GeneratedRouteInvoker invoker) {
        register(owner, methodName, parameterTypes, invoker, null);
    }

    public static synchronized void register(
            Class<?> owner,
            String methodName,
            Class<?>[] parameterTypes,
            GeneratedRouteInvoker invoker,
            GeneratedRouteMetadata metadata) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(invoker, "invoker");
        OwnerRoutes routes = OWNERS.computeIfAbsent(owner, OwnerRoutes::new);
        routes.register(methodName, parameterTypes, invoker, metadata);
    }

    static synchronized GeneratedRouteInvoker find(Method method) {
        OwnerRoutes routes = OWNERS.get(method.getDeclaringClass());
        return routes == null ? null : routes.find(method);
    }

    static synchronized GeneratedRouteMetadata metadata(Method method) {
        OwnerRoutes routes = OWNERS.get(method.getDeclaringClass());
        return routes == null ? null : routes.metadata(method);
    }

    /** Returns only build-time known route methods for the owner. */
    static synchronized Method[] routeMethods(Class<?> owner) {
        OwnerRoutes routes = OWNERS.get(owner);
        return routes == null ? OwnerRoutes.EMPTY_METHODS : routes.methods();
    }

    public static synchronized int size() {
        int size = 0;
        for (OwnerRoutes routes : OWNERS.values()) {
            size += routes.registrations.size();
        }
        return size;
    }

    public static synchronized void releaseStartupMetadata() {
        OWNERS.clear();
    }

    private static final class OwnerRoutes {
        private static final Method[] EMPTY_METHODS = new Method[0];

        private final Class<?> owner;
        private final ArrayList<Registration> registrations = new ArrayList<>(4);
        private Method[] methods;

        private OwnerRoutes(Class<?> owner) {
            this.owner = owner;
        }

        private void register(
                String methodName,
                Class<?>[] parameterTypes,
                GeneratedRouteInvoker invoker,
                GeneratedRouteMetadata metadata) {
            Class<?>[] safeParameterTypes = parameterTypes == null
                    ? Registration.EMPTY_TYPES
                    : parameterTypes.clone();
            for (Registration registration : registrations) {
                if (registration.matches(methodName, safeParameterTypes)) {
                    if (registration.invoker.getClass() != invoker.getClass()) {
                        throw new IllegalStateException(
                                "Generated route invoker already registered: "
                                        + owner.getName() + '#' + methodName);
                    }
                    if (!Objects.equals(registration.metadata, metadata)) {
                        throw new IllegalStateException(
                                "Generated route metadata already registered: "
                                        + owner.getName() + '#' + methodName);
                    }
                    return;
                }
            }
            registrations.add(new Registration(methodName, safeParameterTypes, invoker, metadata));
            methods = null;
        }

        private GeneratedRouteInvoker find(Method method) {
            for (Registration registration : registrations) {
                if (registration.method == method || registration.matches(method)) {
                    return registration.invoker;
                }
            }
            return null;
        }

        private GeneratedRouteMetadata metadata(Method method) {
            for (Registration registration : registrations) {
                if (registration.method == method || registration.matches(method)) {
                    return registration.metadata;
                }
            }
            return null;
        }

        private Method[] methods() {
            Method[] current = methods;
            if (current != null) {
                return current;
            }
            current = new Method[registrations.size()];
            for (int index = 0; index < registrations.size(); index++) {
                Registration registration = registrations.get(index);
                try {
                    Method method = owner.getDeclaredMethod(
                            registration.methodName, registration.parameterTypes);
                    registration.method = method;
                    current[index] = method;
                } catch (NoSuchMethodException failure) {
                    throw new IllegalStateException(
                            "Generated route method no longer exists: "
                                    + owner.getName() + '#' + registration.methodName,
                            failure);
                }
            }
            methods = current;
            return current;
        }
    }

    private static final class Registration {
        private static final Class<?>[] EMPTY_TYPES = new Class<?>[0];

        private final String methodName;
        private final Class<?>[] parameterTypes;
        private final GeneratedRouteInvoker invoker;
        private final GeneratedRouteMetadata metadata;
        private Method method;

        private Registration(
                String methodName,
                Class<?>[] parameterTypes,
                GeneratedRouteInvoker invoker,
                GeneratedRouteMetadata metadata) {
            this.methodName = methodName;
            this.parameterTypes = parameterTypes;
            this.invoker = invoker;
            this.metadata = metadata;
        }

        private boolean matches(Method candidate) {
            if (method != null && method.equals(candidate)) {
                return true;
            }
            return methodName.equals(candidate.getName())
                    && Arrays.equals(parameterTypes, candidate.getParameterTypes());
        }

        private boolean matches(String candidateName, Class<?>[] candidateTypes) {
            return methodName.equals(candidateName) && Arrays.equals(parameterTypes, candidateTypes);
        }
    }
}
