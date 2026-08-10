package com.reactor.rust.di;

import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.logging.FrameworkLogger;
import com.reactor.rust.metrics.Metrics;
import com.reactor.rust.startup.StartupIndex;
import com.reactor.rust.startup.StartupTimeline;
import com.reactor.rust.startup.ApplicationDescriptors;
import com.reactor.rust.startup.StartupMode;
import com.reactor.rust.startup.CompatibilityComponentScanner;

import java.util.ServiceLoader;

/**
 * Scans packages for @Component classes and registers them in BeanContainer.
 *
 * <p>Supports both filesystem and JAR-based class loading.</p>
 */
final class BeanScanner {

    private final BeanContainer container;

    BeanScanner(BeanContainer container) {
        this.container = container;
    }

    /**
     * Scan a package for @Component classes.
     */
    void scanPackage(String packageName) {
        try (StartupTimeline.Scope ignored = StartupTimeline.phase("di.scan." + packageName)) {
            if (scanPackageFromIndex(packageName)) {
                return;
            }
            scanPackageFallback(packageName);
        }
    }

    private boolean scanPackageFromIndex(String packageName) {
        StartupMode.requireDescriptor(packageName);
        int generated = ApplicationDescriptors.registerComponents(container, packageName);
        if (generated > 0 || (StartupMode.isAot()
                && ApplicationDescriptors.hasApplicationDescriptor(packageName))) {
            Metrics.getInstance().setGauge("reactor.startup.generated_components", generated);
            FrameworkLogger.info("[BeanScanner] Generated component factory used for "
                    + packageName + ": classes=" + generated);
            return true;
        }
        if (!PropertiesLoader.getBoolean("reactor.startup.component-index.enabled", true)) {
            return false;
        }
        StartupIndex.IndexResult index = StartupIndex.componentClasses(packageName);
        if (!index.present()) {
            if (PropertiesLoader.getBoolean("reactor.startup.component-index.required", false)) {
                throw new IllegalStateException(
                        "Required startup component index is missing: " + StartupIndex.COMPONENTS_RESOURCE
                );
            }
            return false;
        }
        if (index.entries().isEmpty()) {
            if (PropertiesLoader.getBoolean("reactor.startup.component-index.required", false)) {
                throw new IllegalStateException(
                        "Required startup component index has no entries for package: " + packageName
                );
            }
            return false;
        }

        int processed = 0;
        for (String className : index.entries()) {
            processClass(className, true);
            processed++;
        }
        Metrics.getInstance().setGauge("reactor.startup.component_index.classes", processed);
        FrameworkLogger.info("[BeanScanner] Component index used for " + packageName + ": classes=" + processed);
        return true;
    }

    private void scanPackageFallback(String packageName) {
        if (StartupMode.isAot()) {
            throw new IllegalStateException(
                    "Classpath component scanning is not available in AOT mode: " + packageName);
        }
        if (!PropertiesLoader.getBoolean("reactor.startup.scan.fallback-enabled", true)) {
            throw new IllegalStateException(
                    "Classpath component scan fallback is disabled and no component index was available for "
                            + packageName
            );
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = BeanScanner.class.getClassLoader();
        CompatibilityComponentScanner scanner = ServiceLoader
                .load(CompatibilityComponentScanner.class, loader)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Compatibility startup was selected, but rust-java-rest-compat is not on the classpath"));
        scanner.scan(packageName, container);
    }

    private void processClass(String className, boolean strict) {
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) loader = BeanScanner.class.getClassLoader();
            Class<?> type = Class.forName(className, false, loader);
            if (!container.hasBean(type) && isComponent(type)) {
                container.registerBeanClass(type);
            }
        } catch (ClassNotFoundException | NoClassDefFoundError failure) {
            if (strict) {
                throw new IllegalStateException(
                        "Indexed component cannot be loaded: " + className,
                        failure);
            }
        }
    }

    private static boolean isComponent(Class<?> type) {
        if (type.isAnnotationPresent(com.reactor.rust.di.annotation.Component.class)) {
            return true;
        }
        for (java.lang.annotation.Annotation annotation : type.getAnnotations()) {
            if (annotation.annotationType().isAnnotationPresent(
                    com.reactor.rust.di.annotation.Component.class)) {
                return true;
            }
        }
        return false;
    }

    // Class loading and JAR traversal intentionally live in rust-java-rest-compat.
    @SuppressWarnings("unused")
    private void compatibilityBoundary() {
        if (FrameworkLogger.isDebugEnabled()) {
            FrameworkLogger.debug("[BeanScanner] Compatibility scanner boundary active");
        }
    }
}
