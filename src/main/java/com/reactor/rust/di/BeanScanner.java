package com.reactor.rust.di;

import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.logging.FrameworkLogger;
import com.reactor.rust.metrics.Metrics;
import com.reactor.rust.startup.StartupIndex;
import com.reactor.rust.startup.StartupTimeline;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

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
        if (!PropertiesLoader.getBoolean("reactor.startup.scan.fallback-enabled", true)) {
            throw new IllegalStateException(
                    "Classpath component scan fallback is disabled and no component index was available for "
                            + packageName
            );
        }
        try {
            String path = packageName.replace('.', '/');
            Enumeration<URL> resources = Thread.currentThread()
                    .getContextClassLoader()
                    .getResources(path);

            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                if (resource.getProtocol().equals("file")) {
                    scanDirectory(new File(resource.getFile()), packageName);
                } else if (resource.getProtocol().equals("jar")) {
                    scanJar(resource, packageName);
                }
            }
        } catch (IOException e) {
            FrameworkLogger.warn("[BeanScanner] Error scanning package: " + packageName + " - " + e.getMessage());
        }
    }

    /**
     * Scan a directory for classes.
     */
    private void scanDirectory(File directory, String packageName) {
        if (!directory.exists()) {
            return;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectory(file, packageName + "." + file.getName());
            } else if (file.getName().endsWith(".class")) {
                String className = packageName + "." + file.getName().substring(0, file.getName().length() - 6);
                processClass(className, false);
            }
        }
    }

    /**
     * Scan a JAR file for classes.
     */
    private void scanJar(URL jarUrl, String packageName) {
        String packagePath = packageName.replace('.', '/');

        try {
            JarURLConnection connection = (JarURLConnection) jarUrl.openConnection();
            try (JarFile jar = connection.getJarFile()) {
            Enumeration<JarEntry> entries = jar.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();

                if (name.startsWith(packagePath) && name.endsWith(".class")) {
                    String className = name.replace('/', '.').substring(0, name.length() - 6);
                    processClass(className, false);
                }
            }
            }
        } catch (IOException e) {
            FrameworkLogger.warn("[BeanScanner] Error scanning JAR: " + e.getMessage());
        }
    }

    /**
     * Process a class - check for annotations and register if needed.
     */
    private void processClass(String className, boolean strict) {
        try {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            if (classLoader == null) {
                classLoader = BeanScanner.class.getClassLoader();
            }
            Class<?> clazz = Class.forName(className, false, classLoader);

            // Skip if already registered (use hasBean to avoid exception)
            if (container.hasBean(clazz)) {
                return;
            }

            // Check for component annotations
            if (isComponent(clazz)) {
                container.registerBeanClass(clazz);
            }

        } catch (ClassNotFoundException e) {
            if (strict) {
                throw new IllegalStateException("Indexed component class cannot be loaded: " + className, e);
            }
        } catch (NoClassDefFoundError e) {
            if (strict) {
                throw new IllegalStateException("Indexed component dependency is missing: " + className, e);
            }
        } catch (Exception e) {
            if (strict) {
                throw new IllegalStateException("Indexed component cannot be processed: " + className, e);
            }
        }
    }

    /**
     * Check if a class has @Component (or meta-annotated) annotation.
     */
    private boolean isComponent(Class<?> clazz) {
        // Check direct annotations
        if (clazz.isAnnotationPresent(com.reactor.rust.di.annotation.Component.class)) {
            return true;
        }

        // Check for meta-annotations (@Service, @Repository, @Configuration)
        for (java.lang.annotation.Annotation annotation : clazz.getAnnotations()) {
            if (annotation.annotationType().isAnnotationPresent(com.reactor.rust.di.annotation.Component.class)) {
                return true;
            }
        }

        return false;
    }
}
