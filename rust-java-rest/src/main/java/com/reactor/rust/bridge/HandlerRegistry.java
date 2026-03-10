package com.reactor.rust.bridge;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handler registry with MethodHandle-based invocation.
 * Only V4 signature supported - no fallback.
 */
public class HandlerRegistry {

    private static final HandlerRegistry INSTANCE = new HandlerRegistry();

    public static HandlerRegistry getInstance() {
        return INSTANCE;
    }

    private final Map<Integer, HandlerDescriptor> handlers = new ConcurrentHashMap<>();
    private final List<Object> handlerBeans = new CopyOnWriteArrayList<>();
    private final AtomicInteger idGenerator = new AtomicInteger(1);

    public static class HandlerDescriptor {
        public final Object bean;
        public final Method method;
        public final Class<?> requestType;
        public final Class<?> responseType;
        public final MethodHandle handle;

        public HandlerDescriptor(Object bean,
                Method method,
                Class<?> requestType,
                Class<?> responseType,
                MethodHandle handle) {
            this.bean = bean;
            this.method = method;
            this.requestType = requestType;
            this.responseType = responseType;
            this.handle = handle;
        }
    }

    private HandlerRegistry() {}

    public List<Object> getHandlers() {
        return handlerBeans;
    }

    public void registerBean(Object bean) {
        if (!handlerBeans.contains(bean)) {
            handlerBeans.add(bean);
            System.out.println(">>> [HandlerRegistry] bean registered = " + bean.getClass().getName());
        }
    }

    public boolean isBodyless(int handlerId) {
        HandlerDescriptor desc = handlers.get(handlerId);
        if (desc == null) return false;
        return (desc.requestType == Void.class) || (desc.method.getParameterCount() == 0);
    }

    public int registerHandler(Object bean,
            Method method,
            Class<?> requestType,
            Class<?> responseType) {

        try {
            MethodHandle mh = MethodHandles.lookup()
                    .unreflect(method)
                    .bindTo(bean);

            int id = idGenerator.getAndIncrement();
            handlers.put(id, new HandlerDescriptor(bean, method, requestType, responseType, mh));

            System.out.println("[JAVA] Handler registered: id=" + id
                    + " bean=" + bean.getClass().getName()
                    + " method=" + method.getName()
                    + " reqType=" + requestType.getName()
                    + " respType=" + responseType.getName());

            return id;

        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to create MethodHandle for handler", e);
        }
    }

    /**
     * Single invoke method - V4 signature only.
     * (ByteBuffer, int, byte[], String pathParams, String queryString, String headers)
     * NO FALLBACK - handler must support V4 signature.
     */
    public int invokeBuffered(
            int handlerId,
            ByteBuffer out,
            int offset,
            byte[] inBytes,
            String pathParams,
            String queryString,
            String headers
    ) {
        HandlerDescriptor desc = handlers.get(handlerId);

        if (desc == null) {
            byte[] err = "{\"error\":\"Unknown handlerId\"}".getBytes(StandardCharsets.UTF_8);
            out.position(offset);
            out.put(err);
            return err.length;
        }

        try {
            return (int) desc.handle.invoke(out, offset, inBytes, pathParams, queryString, headers);
        } catch (Throwable e) {
            byte[] err = ("{\"error\":\"" + e.getMessage() + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            out.position(offset);
            out.put(err);
            return err.length;
        }
    }
}
