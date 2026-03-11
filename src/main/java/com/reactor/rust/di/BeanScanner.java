package com.reactor.rust.di;

import java.io.File;
import java.io.IOException;
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
            System.err.println("[BeanScanner] Error scanning package: " + packageName + " - " + e.getMessage());
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
                processClass(className);
            }
        }
    }

    /**
     * Scan a JAR file for classes.
     */
    private void scanJar(URL jarUrl, String packageName) {
        String jarPath = jarUrl.getPath().substring(5, jarUrl.getPath().indexOf("!"));
        String packagePath = packageName.replace('.', '/');

        try (JarFile jar = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jar.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();

                if (name.startsWith(packagePath) && name.endsWith(".class")) {
                    String className = name.replace('/', '.').substring(0, name.length() - 6);
                    processClass(className);
                }
            }
        } catch (IOException e) {
            System.err.println("[BeanScanner] Error scanning JAR: " + e.getMessage());
        }
    }

    /**
     * Process a class - check for annotations and register if needed.
     */
    private void processClass(String className) {
        try {
            Class<?> clazz = Class.forName(className);

            // Skip if already registered
            if (container.getBean(clazz) != null) {
                return;
            }

            // Check for component annotations
            if (isComponent(clazz)) {
                container.registerBeanClass(clazz);
            }

        } catch (ClassNotFoundException e) {
            // Ignore classes that can't be loaded
        } catch (NoClassDefFoundError e) {
            // Ignore classes with missing dependencies
        } catch (Exception e) {
            System.err.println("[BeanScanner] Error processing class: " + className + " - " + e.getMessage());
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
