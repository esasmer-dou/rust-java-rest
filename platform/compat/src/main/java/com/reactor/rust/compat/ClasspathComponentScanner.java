package com.reactor.rust.compat;

import com.reactor.rust.di.BeanContainer;
import com.reactor.rust.di.annotation.Component;
import com.reactor.rust.logging.FrameworkLogger;
import com.reactor.rust.startup.CompatibilityComponentScanner;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Reflection scanner intentionally isolated from the production AOT artifact. */
public final class ClasspathComponentScanner implements CompatibilityComponentScanner {

    @Override
    public void scan(String packageName, BeanContainer container) {
        String resourcePath = packageName.replace('.', '/');
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = ClasspathComponentScanner.class.getClassLoader();
        try {
            Enumeration<URL> resources = loader.getResources(resourcePath);
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                switch (resource.getProtocol()) {
                    case "file" -> scanDirectory(Path.of(URI.create(resource.toString())), packageName, container, loader);
                    case "jar" -> scanJar(resource, resourcePath, container, loader);
                    default -> FrameworkLogger.debug(
                            "[compat] Unsupported classpath protocol: " + resource.getProtocol());
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot scan compatibility package " + packageName, failure);
        }
    }

    private static void scanDirectory(
            Path directory,
            String packageName,
            BeanContainer container,
            ClassLoader loader) throws IOException {
        if (!Files.isDirectory(directory)) return;
        try (var entries = Files.list(directory)) {
            for (Path entry : entries.toList()) {
                if (Files.isDirectory(entry)) {
                    scanDirectory(entry, packageName + "." + entry.getFileName(), container, loader);
                } else {
                    String fileName = entry.getFileName().toString();
                    if (isClassFile(fileName)) {
                        process(packageName + "." + fileName.substring(0, fileName.length() - 6),
                                container, loader);
                    }
                }
            }
        }
    }

    private static void scanJar(
            URL resource,
            String packagePath,
            BeanContainer container,
            ClassLoader loader) throws IOException {
        JarURLConnection connection = (JarURLConnection) resource.openConnection();
        connection.setUseCaches(false);
        try (JarFile jar = connection.getJarFile()) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith(packagePath) && isClassFile(name)) {
                    process(name.substring(0, name.length() - 6).replace('/', '.'), container, loader);
                }
            }
        }
    }

    private static boolean isClassFile(String name) {
        return name.endsWith(".class")
                && !name.endsWith("module-info.class")
                && !name.endsWith("package-info.class");
    }

    private static void process(String className, BeanContainer container, ClassLoader loader) {
        try {
            Class<?> type = Class.forName(className, false, loader);
            if (!container.hasBean(type) && isComponent(type)) {
                container.registerBeanClass(type);
            }
        } catch (ClassNotFoundException | LinkageError failure) {
            FrameworkLogger.debug("[compat] Skipped " + className + ": " + failure.getMessage());
        }
    }

    private static boolean isComponent(Class<?> type) {
        if (type.isAnnotationPresent(Component.class)) return true;
        for (java.lang.annotation.Annotation annotation : type.getAnnotations()) {
            if (annotation.annotationType().isAnnotationPresent(Component.class)) return true;
        }
        return false;
    }
}
