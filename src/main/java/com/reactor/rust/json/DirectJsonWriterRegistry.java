package com.reactor.rust.json;

import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Exact-class registry for generated/manual direct JSON writers.
 *
 * <p>This deliberately avoids assignable/reflection matching. A writer must be
 * explicitly registered for the DTO class so hot-path lookup is predictable and
 * cacheable.</p>
 */
public final class DirectJsonWriterRegistry {

    private static final CopyOnWriteArrayList<DirectJsonWriterProvider> PROVIDERS = new CopyOnWriteArrayList<>();
    private static final ConcurrentMap<Class<?>, DirectJsonWriter<?>> WRITERS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, DirectJsonWriter<?>> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, Boolean> MISSES = new ConcurrentHashMap<>();

    static {
        ClassLoader classLoader = DirectJsonWriterRegistry.class.getClassLoader();
        if (classLoader == null) {
            classLoader = ClassLoader.getSystemClassLoader();
        }
        for (DirectJsonWriterProvider provider : ServiceLoader.load(DirectJsonWriterProvider.class, classLoader)) {
            PROVIDERS.add(provider);
        }
    }

    private DirectJsonWriterRegistry() {
    }

    public static <T> void register(Class<T> type, DirectJsonWriter<? super T> writer) {
        if (type == null || writer == null) {
            throw new IllegalArgumentException("type and writer are required");
        }
        WRITERS.put(type, writer);
        CACHE.remove(type);
        MISSES.remove(type);
    }

    public static void registerProvider(DirectJsonWriterProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider is required");
        }
        PROVIDERS.add(provider);
        CACHE.clear();
        MISSES.clear();
    }

    @SuppressWarnings("unchecked")
    public static <T> DirectJsonWriter<T> findWriter(Class<T> type) {
        if (type == null) {
            return null;
        }
        DirectJsonWriter<?> explicit = WRITERS.get(type);
        if (explicit != null) {
            return (DirectJsonWriter<T>) explicit;
        }
        DirectJsonWriter<?> cached = CACHE.get(type);
        if (cached != null) {
            return (DirectJsonWriter<T>) cached;
        }
        if (MISSES.containsKey(type)) {
            return null;
        }

        for (DirectJsonWriterProvider provider : PROVIDERS) {
            DirectJsonWriter<?> writer = provider.findWriter(type);
            if (writer != null) {
                DirectJsonWriter<?> previous = CACHE.putIfAbsent(type, writer);
                return (DirectJsonWriter<T>) (previous != null ? previous : writer);
            }
        }

        MISSES.put(type, Boolean.TRUE);
        return null;
    }

    static void clearForTests() {
        WRITERS.clear();
        CACHE.clear();
        MISSES.clear();
        PROVIDERS.clear();
    }
}
