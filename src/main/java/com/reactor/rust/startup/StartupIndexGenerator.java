package com.reactor.rust.startup;

import com.reactor.rust.annotations.DeleteMapping;
import com.reactor.rust.annotations.GetMapping;
import com.reactor.rust.annotations.PatchMapping;
import com.reactor.rust.annotations.PostMapping;
import com.reactor.rust.annotations.PutMapping;
import com.reactor.rust.annotations.RequestMapping;
import com.reactor.rust.annotations.RustRoute;
import com.reactor.rust.di.annotation.Component;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Build-time helper that writes startup indexes for applications.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * java -cp app.jar:lib/* com.reactor.rust.startup.StartupIndexGenerator \
 *   --output target/classes --packages com.example.app
 * }</pre>
 */
public final class StartupIndexGenerator {

    private StartupIndexGenerator() {
    }

    public static void main(String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        if (arguments.packages().isEmpty()) {
            throw new IllegalArgumentException("--packages is required");
        }
        Set<String> components = new LinkedHashSet<>();
        Set<String> routes = new LinkedHashSet<>();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        for (String packageName : arguments.packages()) {
            for (String className : classNames(packageName, classLoader)) {
                Class<?> clazz = Class.forName(className, false, classLoader);
                if (isComponent(clazz)) {
                    components.add(className);
                    collectRoutes(clazz, routes);
                }
            }
        }

        Path metaInf = arguments.output().resolve("META-INF").resolve("reactor");
        Files.createDirectories(metaInf);
        Files.write(metaInf.resolve("components.idx"), components, StandardCharsets.UTF_8);
        Files.write(metaInf.resolve("routes.idx"), routes, StandardCharsets.UTF_8);
        System.out.println("components=" + components.size() + " routes=" + routes.size()
                + " output=" + metaInf.toAbsolutePath());
    }

    private static List<String> classNames(String packageName, ClassLoader classLoader) throws IOException {
        String path = packageName.replace('.', '/');
        Enumeration<URL> resources = classLoader.getResources(path);
        List<String> names = new ArrayList<>();
        while (resources.hasMoreElements()) {
            URL resource = resources.nextElement();
            if ("file".equals(resource.getProtocol())) {
                scanDirectory(fileFromUrl(resource), packageName, names);
            } else if ("jar".equals(resource.getProtocol())) {
                scanJar(resource, packageName, names);
            }
        }
        return names;
    }

    private static void scanDirectory(File directory, String packageName, List<String> names) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectory(file, packageName + "." + file.getName(), names);
            } else if (file.getName().endsWith(".class")) {
                names.add(packageName + "." + file.getName().substring(0, file.getName().length() - 6));
            }
        }
    }

    private static void scanJar(URL jarUrl, String packageName, List<String> names) throws IOException {
        String packagePath = packageName.replace('.', '/');
        JarURLConnection connection = (JarURLConnection) jarUrl.openConnection();
        try (JarFile jar = connection.getJarFile()) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith(packagePath) && name.endsWith(".class")) {
                    names.add(name.replace('/', '.').substring(0, name.length() - 6));
                }
            }
        }
    }

    private static File fileFromUrl(URL url) throws IOException {
        try {
            return Path.of(url.toURI()).toFile();
        } catch (URISyntaxException e) {
            throw new IOException("Invalid classpath URL: " + url, e);
        }
    }

    private static boolean isComponent(Class<?> clazz) {
        if (clazz.isAnnotationPresent(Component.class)) {
            return true;
        }
        for (Annotation annotation : clazz.getAnnotations()) {
            if (annotation.annotationType().isAnnotationPresent(Component.class)) {
                return true;
            }
        }
        return false;
    }

    private static void collectRoutes(Class<?> clazz, Set<String> routes) {
        String basePath = "";
        RequestMapping classMapping = clazz.getAnnotation(RequestMapping.class);
        if (classMapping != null) {
            basePath = normalizeBasePath(classMapping.value());
        }
        for (Method method : clazz.getDeclaredMethods()) {
            routeLine(method, basePath, clazz.getName()).ifPresent(routes::add);
        }
    }

    private static java.util.Optional<String> routeLine(Method method, String basePath, String className) {
        RustRoute rustRoute = method.getAnnotation(RustRoute.class);
        if (rustRoute != null) {
            return java.util.Optional.of(rustRoute.method().toUpperCase(java.util.Locale.ROOT) + " "
                    + combine(basePath, rustRoute.path()) + " " + className + "#" + method.getName());
        }
        GetMapping get = method.getAnnotation(GetMapping.class);
        if (get != null) {
            return java.util.Optional.of("GET " + combine(basePath, get.value()) + " " + className + "#" + method.getName());
        }
        PostMapping post = method.getAnnotation(PostMapping.class);
        if (post != null) {
            return java.util.Optional.of("POST " + combine(basePath, post.value()) + " " + className + "#" + method.getName());
        }
        PutMapping put = method.getAnnotation(PutMapping.class);
        if (put != null) {
            return java.util.Optional.of("PUT " + combine(basePath, put.value()) + " " + className + "#" + method.getName());
        }
        DeleteMapping delete = method.getAnnotation(DeleteMapping.class);
        if (delete != null) {
            return java.util.Optional.of("DELETE " + combine(basePath, delete.value()) + " " + className + "#" + method.getName());
        }
        PatchMapping patch = method.getAnnotation(PatchMapping.class);
        if (patch != null) {
            return java.util.Optional.of("PATCH " + combine(basePath, patch.value()) + " " + className + "#" + method.getName());
        }
        return java.util.Optional.empty();
    }

    private static String normalizeBasePath(String basePath) {
        if (basePath == null || basePath.isBlank()) {
            return "";
        }
        String value = basePath.startsWith("/") ? basePath : "/" + basePath;
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String combine(String basePath, String path) {
        String route = path == null || path.isBlank() ? "/" : path;
        if (!route.startsWith("/")) {
            route = "/" + route;
        }
        return (basePath + route).replaceAll("//+", "/");
    }

    private record Arguments(Path output, List<String> packages) {
        static Arguments parse(String[] args) {
            Path output = Path.of("target/classes");
            List<String> packages = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--output" -> output = Path.of(nextValue(args, ++i, "--output"));
                    case "--packages" -> {
                        for (String token : nextValue(args, ++i, "--packages").split(",")) {
                            String pkg = token.trim();
                            if (!pkg.isEmpty()) {
                                packages.add(pkg);
                            }
                        }
                    }
                    default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
                }
            }
            return new Arguments(output, packages);
        }

        private static String nextValue(String[] args, int index, String name) {
            if (index >= args.length || args[index].startsWith("--")) {
                throw new IllegalArgumentException(name + " requires a value");
            }
            return args[index];
        }
    }
}
