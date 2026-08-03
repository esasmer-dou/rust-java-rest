package com.reactor.rust.startup;

import com.reactor.rust.di.BeanContainer;
import com.reactor.rust.bridge.HandlerRegistry;

import java.util.List;

/** Build-time generated application metadata and component factory contract. */
public interface ApplicationDescriptor {

    List<String> components();

    List<String> routes();

    List<String> properties();

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
}
