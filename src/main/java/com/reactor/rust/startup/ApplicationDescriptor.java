package com.reactor.rust.startup;

import com.reactor.rust.di.BeanContainer;
import com.reactor.rust.bridge.HandlerRegistry;
import com.reactor.rust.exception.ExceptionHandlerRegistry;

import java.util.List;

/** Build-time generated application metadata and component factory contract. */
public interface ApplicationDescriptor {

    List<String> components();

    List<String> routes();

    List<String> properties();

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
