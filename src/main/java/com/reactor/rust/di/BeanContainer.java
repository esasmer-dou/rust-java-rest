package com.reactor.rust.di;

import com.reactor.rust.di.annotation.*;
import com.reactor.rust.di.exception.BeanCreationException;
import com.reactor.rust.di.exception.CircularDependencyException;
import com.reactor.rust.di.exception.NoSuchBeanException;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Zero-Overhead Dependency Injection Container
 *
 * <p>A lightweight, high-performance DI container inspired by Spring but designed for
 * ultra-low latency applications. All dependency resolution happens at startup,
 * leaving zero runtime overhead.</p>
 *
 * <h2>Features:</h2>
 * <ul>
 *   <li>Annotation-based configuration (@Component, @Service, @Repository, @Configuration)</li>
 *   <li>Field injection via @Autowired</li>
 *   <li>Lifecycle callbacks (@PostConstruct, @PreDestroy)</li>
 *   <li>@Bean methods in @Configuration classes</li>
 *   <li>Primary bean selection with @Primary</li>
 *   <li>Qualifier-based disambiguation with @Qualifier</li>
 *   <li>Zero runtime reflection - all resolved at startup</li>
 * </ul>
 *
 * <h2>Usage:</h2>
 * <pre>{@code
 * // Initialize container
 * BeanContainer container = BeanContainer.create();
 * container.scan("com.myapp");
 * container.start();
 *
 * // Get beans (zero-overhead lookup)
 * UserService userService = container.getBean(UserService.class);
 *
 * // Or register manually
 * container.registerBean(UserService.class, new UserServiceImpl());
 * }</pre>
 *
 * <h2>Performance:</h2>
 * <ul>
 *   <li>Bean lookup: O(1) ConcurrentHashMap lookup</li>
 *   <li>Memory: ~50-100 bytes per bean overhead</li>
 *   <li>Startup: O(n) where n = number of beans</li>
 * </ul>
 *
 * @author Mustafa
 * @since 1.0.0
 */
public final class BeanContainer {

    // ========================================
    // Singleton Instance
    // ========================================

    private static volatile BeanContainer instance;
    private static final Object LOCK = new Object();

    /**
     * Get the global singleton container instance.
     */
    public static BeanContainer getInstance() {
        BeanContainer result = instance;
        if (result == null) {
            synchronized (LOCK) {
                result = instance;
                if (result == null) {
                    instance = result = new BeanContainer();
                }
            }
        }
        return result;
    }

    /**
     * Create a new container instance (for testing or isolated contexts).
     */
    public static BeanContainer create() {
        return new BeanContainer();
    }

    // ========================================
    // Internal State
    // ========================================

    // Bean storage - optimized for fast lookup
    private final Map<Class<?>, Object> beansByType = new ConcurrentHashMap<>(64);
    private final Map<String, Object> beansByName = new ConcurrentHashMap<>(64);
    private final Map<Class<?>, String> beanNames = new ConcurrentHashMap<>(64);
    private final Set<Class<?>> primaryBeans = ConcurrentHashMap.newKeySet();
    private final List<Runnable> preDestroyCallbacks = new ArrayList<>();

    // State tracking
    private volatile boolean initialized = false;
    private volatile boolean scanning = false;

    private BeanContainer() {}

    // ========================================
    // Bean Registration (Manual)
    // ========================================

    /**
     * Register a bean instance with automatic name generation.
     *
     * @param type Bean type
     * @param instance Bean instance
     * @return this container for chaining
     */
    public <T> BeanContainer registerBean(Class<T> type, T instance) {
        return registerBean(type, instance, generateBeanName(type), false);
    }

    /**
     * Register a bean instance with custom name.
     *
     * @param type Bean type
     * @param instance Bean instance
     * @param name Bean name
     * @return this container for chaining
     */
    public <T> BeanContainer registerBean(Class<T> type, T instance, String name) {
        return registerBean(type, instance, name, false);
    }

    /**
     * Register a bean with primary flag.
     */
    public <T> BeanContainer registerBean(Class<T> type, T instance, String name, boolean primary) {
        Objects.requireNonNull(type, "Bean type cannot be null");
        Objects.requireNonNull(instance, "Bean instance cannot be null");
        Objects.requireNonNull(name, "Bean name cannot be null");

        beansByType.put(type, instance);
        beansByName.put(name, instance);
        beanNames.put(type, name);

        if (primary) {
            primaryBeans.add(type);
        }

        // Register all interfaces and superclasses
        registerTypeHierarchy(type, instance);

        return this;
    }

    /**
     * Internal method to register bean with wildcard class type.
     * Used by component scanning and @Bean processing.
     */
    @SuppressWarnings("unchecked")
    private BeanContainer registerBeanInternal(Class<?> type, Object instance, String name, boolean primary) {
        Objects.requireNonNull(type, "Bean type cannot be null");
        Objects.requireNonNull(instance, "Bean instance cannot be null");
        Objects.requireNonNull(name, "Bean name cannot be null");

        beansByType.put(type, instance);
        beansByName.put(name, instance);
        beanNames.put(type, name);

        if (primary) {
            primaryBeans.add(type);
        }

        // Register all interfaces and superclasses
        registerTypeHierarchy(type, instance);

        return this;
    }

    /**
     * Register a lazy bean supplier.
     */
    public <T> BeanContainer registerLazy(Class<T> type, Supplier<T> supplier, String name) {
        Objects.requireNonNull(type, "Bean type cannot be null");
        Objects.requireNonNull(supplier, "Supplier cannot be null");

        // Lazy wrapper
        Supplier<T> lazySupplier = () -> {
            T instance = supplier.get();
            beansByType.put(type, instance);
            beansByName.put(name, instance);
            return instance;
        };

        // Store supplier temporarily (will be resolved on first access)
        lazySuppliers.put(type, lazySupplier);
        beanNames.put(type, name);

        return this;
    }

    private final Map<Class<?>, Supplier<?>> lazySuppliers = new ConcurrentHashMap<>();

    // ========================================
    // Bean Lookup (Zero-Overhead)
    // ========================================

    /**
     * Get a bean by type. O(1) lookup.
     *
     * @param type Bean type
     * @return Bean instance
     * @throws NoSuchBeanException if bean not found
     */
    @SuppressWarnings("unchecked")
    public <T> T getBean(Class<T> type) {
        Objects.requireNonNull(type, "Bean type cannot be null");

        // Check resolved beans first
        Object bean = beansByType.get(type);

        // Check lazy suppliers
        if (bean == null) {
            Supplier<?> supplier = lazySuppliers.get(type);
            if (supplier != null) {
                bean = supplier.get();
                lazySuppliers.remove(type);
            }
        }

        if (bean == null) {
            throw new NoSuchBeanException("No bean of type '" + type.getName() + "' found");
        }

        return (T) bean;
    }

    /**
     * Get a bean by name. O(1) lookup.
     *
     * @param name Bean name
     * @return Bean instance
     * @throws NoSuchBeanException if bean not found
     */
    @SuppressWarnings("unchecked")
    public <T> T getBean(String name) {
        Objects.requireNonNull(name, "Bean name cannot be null");

        Object bean = beansByName.get(name);
        if (bean == null) {
            throw new NoSuchBeanException("No bean with name '" + name + "' found");
        }
        return (T) bean;
    }

    /**
     * Get a bean by type and name (for qualified injection).
     */
    @SuppressWarnings("unchecked")
    public <T> T getBean(Class<T> type, String name) {
        Object bean = beansByName.get(name);
        if (bean != null && type.isInstance(bean)) {
            return (T) bean;
        }
        return getBean(type);
    }

    /**
     * Check if a bean exists.
     */
    public boolean hasBean(Class<?> type) {
        return beansByType.containsKey(type) || lazySuppliers.containsKey(type);
    }

    /**
     * Check if a bean exists by name.
     */
    public boolean hasBean(String name) {
        return beansByName.containsKey(name);
    }

    /**
     * Get all beans of a type.
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> getBeansOfType(Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Map.Entry<Class<?>, Object> entry : beansByType.entrySet()) {
            if (type.isAssignableFrom(entry.getKey())) {
                result.add((T) entry.getValue());
            }
        }
        return result;
    }

    /**
     * Get all bean names.
     */
    public Set<String> getBeanNames() {
        return Collections.unmodifiableSet(beansByName.keySet());
    }

    /**
     * Get bean name for a type.
     */
    public String getBeanName(Class<?> type) {
        return beanNames.get(type);
    }

    // ========================================
    // Component Scanning
    // ========================================

    /**
     * Scan packages for @Component, @Service, @Repository, @Configuration classes.
     *
     * @param basePackages Packages to scan
     * @return this container for chaining
     */
    public BeanContainer scan(String... basePackages) {
        if (scanning) {
            throw new IllegalStateException("Container is already scanning");
        }
        scanning = true;

        try {
            BeanScanner scanner = new BeanScanner(this);

            for (String pkg : basePackages) {
                scanner.scanPackage(pkg);
            }

            // Process @Configuration classes
            for (Object configBean : new ArrayList<>(beansByType.values())) {
                if (configBean.getClass().isAnnotationPresent(Configuration.class)) {
                    processConfiguration(configBean);
                }
            }

        } finally {
            scanning = false;
        }

        return this;
    }

    /**
     * Start the container - resolves all dependencies.
     */
    public void start() {
        if (initialized) {
            return;
        }

        // Resolve all lazy suppliers first
        for (Map.Entry<Class<?>, Supplier<?>> entry : new ArrayList<>(lazySuppliers.entrySet())) {
            if (!beansByType.containsKey(entry.getKey())) {
                Object bean = entry.getValue().get();
                beansByType.put(entry.getKey(), bean);
                String name = beanNames.get(entry.getKey());
                if (name != null) {
                    beansByName.put(name, bean);
                }
            }
        }
        lazySuppliers.clear();

        // Inject dependencies into all beans
        injectAll();

        // Call @PostConstruct on all beans
        invokePostConstruct();

        initialized = true;

        System.out.println("[BeanContainer] Started with " + beansByType.size() + " beans");
    }

    /**
     * Shutdown the container - call @PreDestroy callbacks.
     */
    public void shutdown() {
        for (Runnable callback : preDestroyCallbacks) {
            try {
                callback.run();
            } catch (Exception e) {
                System.err.println("[BeanContainer] Error during shutdown: " + e.getMessage());
            }
        }
        preDestroyCallbacks.clear();
        beansByType.clear();
        beansByName.clear();
        beanNames.clear();
        primaryBeans.clear();
        initialized = false;

        System.out.println("[BeanContainer] Shutdown complete");
    }

    // ========================================
    // Internal Methods
    // ========================================

    /**
     * Register type hierarchy for interface-based lookup.
     */
    private void registerTypeHierarchy(Class<?> type, Object instance) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            // Register interfaces
            for (Class<?> iface : current.getInterfaces()) {
                if (!beansByType.containsKey(iface)) {
                    beansByType.put(iface, instance);
                }
            }
            current = current.getSuperclass();
        }
    }

    /**
     * Generate bean name from class.
     */
    private String generateBeanName(Class<?> type) {
        String simpleName = type.getSimpleName();
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }

    /**
     * Process @Configuration class and register @Bean methods.
     */
    private void processConfiguration(Object configBean) {
        Class<?> configClass = configBean.getClass();

        for (Method method : configClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Bean.class)) {
                try {
                    method.setAccessible(true);

                    Bean beanAnnotation = method.getAnnotation(Bean.class);
                    String beanName = beanAnnotation.value().isEmpty()
                            ? method.getName()
                            : beanAnnotation.value();

                    Object beanInstance = method.invoke(configBean);

                    if (beanInstance != null) {
                        Class<?> returnType = method.getReturnType();
                        boolean isPrimary = returnType.isAnnotationPresent(Primary.class);

                        registerBeanInternal(returnType, beanInstance, beanName, isPrimary);

                        System.out.println("[BeanContainer] @Bean registered: " + beanName + " -> " + returnType.getSimpleName());
                    }

                } catch (Exception e) {
                    throw new BeanCreationException("Failed to create @Bean: " + method.getName(), e);
                }
            }
        }
    }

    /**
     * Inject dependencies into all registered beans.
     */
    private void injectAll() {
        Set<Object> injected = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> beingInjected = Collections.newSetFromMap(new IdentityHashMap<>());

        for (Object bean : new ArrayList<>(beansByType.values())) {
            injectDependencies(bean, injected, beingInjected);
        }
    }

    /**
     * Inject @Autowired fields into a bean.
     */
    void injectDependencies(Object bean, Set<Object> injected, Set<Object> beingInjected) {
        if (injected.contains(bean)) {
            return;
        }

        if (beingInjected.contains(bean)) {
            throw new CircularDependencyException("Circular dependency detected for: " + bean.getClass().getName());
        }

        beingInjected.add(bean);

        try {
            Class<?> clazz = bean.getClass();

            while (clazz != null && clazz != Object.class) {
                for (Field field : clazz.getDeclaredFields()) {
                    if (field.isAnnotationPresent(Autowired.class)) {
                        injectField(bean, field);
                    }
                }
                clazz = clazz.getSuperclass();
            }

            injected.add(bean);

        } finally {
            beingInjected.remove(bean);
        }
    }

    /**
     * Inject a single @Autowired field.
     */
    private void injectField(Object bean, Field field) {
        try {
            Class<?> fieldType = field.getType();
            Object dependency;

            // Check for @Qualifier
            Qualifier qualifier = field.getAnnotation(Qualifier.class);
            if (qualifier != null) {
                dependency = getBean(fieldType, qualifier.value());
            } else {
                // Check for primary or single candidate
                dependency = resolveDependency(fieldType);
            }

            if (dependency == null) {
                Autowired autowired = field.getAnnotation(Autowired.class);
                if (autowired.required()) {
                    throw new NoSuchBeanException("Required bean not found for field: " + field.getName());
                }
                return;
            }

            field.setAccessible(true);
            field.set(bean, dependency);

        } catch (NoSuchBeanException e) {
            throw e;
        } catch (Exception e) {
            throw new BeanCreationException("Failed to inject field: " + field.getName(), e);
        }
    }

    /**
     * Resolve a dependency by type.
     */
    private Object resolveDependency(Class<?> type) {
        // Check primary beans first
        for (Class<?> primaryType : primaryBeans) {
            if (type.isAssignableFrom(primaryType)) {
                return beansByType.get(primaryType);
            }
        }

        // Direct lookup
        return beansByType.get(type);
    }

    /**
     * Call @PostConstruct methods on all beans.
     */
    private void invokePostConstruct() {
        for (Object bean : new ArrayList<>(beansByType.values())) {
            invokePostConstruct(bean);
        }
    }

    /**
     * Call @PostConstruct methods on a bean.
     */
    private void invokePostConstruct(Object bean) {
        Class<?> clazz = bean.getClass();

        while (clazz != null && clazz != Object.class) {
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.isAnnotationPresent(PostConstruct.class)) {
                    try {
                        method.setAccessible(true);
                        method.invoke(bean);
                    } catch (Exception e) {
                        throw new BeanCreationException("@PostConstruct failed: " + method.getName(), e);
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }

        // Register @PreDestroy callbacks
        registerPreDestroyCallbacks(bean);
    }

    /**
     * Register @PreDestroy callbacks for a bean.
     */
    private void registerPreDestroyCallbacks(Object bean) {
        Class<?> clazz = bean.getClass();

        while (clazz != null && clazz != Object.class) {
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.isAnnotationPresent(PreDestroy.class)) {
                    preDestroyCallbacks.add(() -> {
                        try {
                            method.setAccessible(true);
                            method.invoke(bean);
                        } catch (Exception e) {
                            System.err.println("[BeanContainer] @PreDestroy failed: " + e.getMessage());
                        }
                    });
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    /**
     * Create and register a bean from a class.
     */
    public void registerBeanClass(Class<?> beanClass) {
        try {
            // Check for stereotype annotation
            Component component = findComponentAnnotation(beanClass);
            if (component == null) {
                return;
            }

            String beanName = component.value().isEmpty()
                    ? generateBeanName(beanClass)
                    : component.value();

            boolean isPrimary = beanClass.isAnnotationPresent(Primary.class);

            // Create instance
            Object instance = createInstance(beanClass);

            // Register bean
            registerBeanInternal(beanClass, instance, beanName, isPrimary);

            System.out.println("[BeanContainer] @Component registered: " + beanName + " -> " + beanClass.getSimpleName());

        } catch (Exception e) {
            throw new BeanCreationException("Failed to create bean: " + beanClass.getName(), e);
        }
    }

    /**
     * Find @Component annotation (including meta-annotations like @Service).
     */
    private Component findComponentAnnotation(Class<?> clazz) {
        // Direct annotation
        Component component = clazz.getAnnotation(Component.class);
        if (component != null) {
            return component;
        }

        // Meta-annotations (@Service, @Repository, @Configuration)
        for (Annotation annotation : clazz.getAnnotations()) {
            Component metaComponent = annotation.annotationType().getAnnotation(Component.class);
            if (metaComponent != null) {
                return metaComponent;
            }
        }

        return null;
    }

    /**
     * Create an instance using default constructor or @Autowired constructor.
     */
    private Object createInstance(Class<?> clazz) throws Exception {
        // Look for @Autowired constructor
        Constructor<?>[] constructors = clazz.getDeclaredConstructors();
        Constructor<?> selectedConstructor = null;

        for (Constructor<?> constructor : constructors) {
            if (constructor.isAnnotationPresent(Autowired.class)) {
                selectedConstructor = constructor;
                break;
            }
        }

        // Fall back to default constructor
        if (selectedConstructor == null) {
            try {
                selectedConstructor = clazz.getDeclaredConstructor();
            } catch (NoSuchMethodException e) {
                throw new BeanCreationException("No default constructor found for: " + clazz.getName());
            }
        }

        selectedConstructor.setAccessible(true);

        // Resolve constructor parameters
        Class<?>[] paramTypes = selectedConstructor.getParameterTypes();
        if (paramTypes.length == 0) {
            return selectedConstructor.newInstance();
        }

        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            args[i] = resolveDependency(paramTypes[i]);
            if (args[i] == null) {
                throw new NoSuchBeanException("Constructor dependency not found: " + paramTypes[i].getName());
            }
        }

        return selectedConstructor.newInstance(args);
    }

    /**
     * Check if container is initialized.
     */
    public boolean isInitialized() {
        return initialized;
    }
}
