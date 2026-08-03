package com.reactor.rust.startup;

import com.reactor.rust.di.BeanContainer;
import com.reactor.rust.bridge.HandlerRegistry;

import java.util.List;
import java.util.ServiceLoader;

/** Loads generated application descriptors without classpath scanning. */
public final class ApplicationDescriptors {

    private static final List<ApplicationDescriptor> DESCRIPTORS = load();

    private ApplicationDescriptors() {}

    public static int registerComponents(BeanContainer container, String basePackage) {
        int registered = 0;
        for (ApplicationDescriptor descriptor : DESCRIPTORS) {
            registered += descriptor.registerComponents(container, basePackage);
        }
        return registered;
    }

    public static int registerHandlers(
            BeanContainer container,
            HandlerRegistry registry,
            String basePackage) {
        int registered = 0;
        for (ApplicationDescriptor descriptor : DESCRIPTORS) {
            registered += descriptor.registerHandlers(container, registry, basePackage);
        }
        return registered;
    }

    public static int registerConfigurationBeans(BeanContainer container, String basePackage) {
        int registered = 0;
        for (ApplicationDescriptor descriptor : DESCRIPTORS) {
            registered += descriptor.registerConfigurationBeans(container, basePackage);
        }
        return registered;
    }

    public static int descriptorCount() {
        return DESCRIPTORS.size();
    }

    private static List<ApplicationDescriptor> load() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = ApplicationDescriptors.class.getClassLoader();
        }
        return ServiceLoader.load(ApplicationDescriptor.class, loader).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
    }
}
