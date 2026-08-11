package com.reactor.rust.startup;

import com.reactor.rust.di.BeanContainer;
import com.reactor.rust.bridge.HandlerRegistry;
import com.reactor.rust.exception.ExceptionHandlerRegistry;

import java.util.List;

/** Build-time generated application metadata and component factory contract. */
public interface ApplicationDescriptor {

    default List<String> components() {
        return List.of();
    }

    default List<String> routes() {
        return List.of();
    }

    default List<String> properties() {
        return List.of();
    }

    /** Compact package ownership check used by strict-AOT startup. */
    default boolean coversPackage(String basePackage) {
        String prefix = basePackage == null || basePackage.isBlank() ? "" : basePackage + ".";
        if (prefix.isEmpty()) {
            return !components().isEmpty() || !routes().isEmpty();
        }
        for (String component : components()) {
            if (component.equals(basePackage) || component.startsWith(prefix)) return true;
        }
        for (String route : routes()) {
            String[] columns = route.split("\\s+", 3);
            if (columns.length < 3) continue;
            String owner = columns[2];
            int methodSeparator = owner.indexOf('#');
            if (methodSeparator > 0) owner = owner.substring(0, methodSeparator);
            if (owner.equals(basePackage) || owner.startsWith(prefix)) return true;
        }
        return false;
    }

    /** Request/response types with generated validation metadata. */
    default List<String> validators() {
        return List.of();
    }

    /** Request/response types known to the build-time serialization plan. */
    default List<String> codecs() {
        return List.of();
    }

    /** Conditional component declarations evaluated once during startup. */
    default List<String> conditions() {
        return List.of();
    }

    /** Generated health/liveness/readiness route declarations. */
    default List<String> healthRoutes() {
        return List.of();
    }

    /** Evaluates a build-time-known component condition once during startup. */
    default boolean isComponentEnabled(String componentType) {
        return true;
    }

    int registerComponents(BeanContainer container, String basePackage);

    /** Registers generated route owners without scanning application classes. */
    default int registerHandlers(
            BeanContainer container,
            HandlerRegistry registry,
            String basePackage) {
        return 0;
    }

    /** Invokes generated {@code @Bean} factories after component definitions are registered. */
    default int registerConfigurationBeans(BeanContainer container, String basePackage) {
        return 0;
    }

    /** Registers build-time generated exception handler invokers. */
    default int registerExceptionHandlers(
            BeanContainer container,
            ExceptionHandlerRegistry registry,
            String basePackage) {
        return 0;
    }

    /** Registers optional build-time generated starter integrations such as scheduled tasks. */
    default int registerExtensions(BeanContainer container, String basePackage) {
        return 0;
    }
}
