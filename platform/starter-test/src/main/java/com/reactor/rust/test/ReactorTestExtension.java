package com.reactor.rust.test;

import com.reactor.rust.app.ApplicationContext;
import com.reactor.rust.app.RestApplication;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/** JUnit lifecycle adapter for the process-wide native HTTP runtime. */
public final class ReactorTestExtension implements BeforeAllCallback, AfterAllCallback, ParameterResolver {
    private static final ReentrantLock NATIVE_SERVER_LOCK = new ReentrantLock();
    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(ReactorTestExtension.class);

    @Override
    public void beforeAll(ExtensionContext context) {
        NATIVE_SERVER_LOCK.lock();
        State state = null;
        Map<String, String> previous = new LinkedHashMap<>();
        try {
            ReactorTest config = context.getRequiredTestClass().getAnnotation(ReactorTest.class);
            if (config == null) throw new IllegalStateException("@ReactorTest is missing");
            previous.putAll(apply(config.properties()));
            if (!previous.containsKey("server.port")) {
                previous.put("server.port", System.getProperty("server.port"));
            }
            System.setProperty("server.port", "0");
            RestApplication.RunningApplication application = RestApplication.startAsync(config.application());
            state = new State(application, new ReactorTestClient(application.port()), previous);
            context.getStore(NAMESPACE).put(context.getRequiredTestClass(), state);
        } catch (RuntimeException | Error failure) {
            if (state != null) state.close();
            else {
                restore(previous);
                NATIVE_SERVER_LOCK.unlock();
            }
            throw failure;
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        State state = context.getStore(NAMESPACE).remove(context.getRequiredTestClass(), State.class);
        if (state != null) state.close();
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        Class<?> type = parameter.getParameter().getType();
        return type == ReactorTestClient.class
                || type == RestApplication.RunningApplication.class
                || type == ApplicationContext.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        State state = context.getStore(NAMESPACE).get(context.getRequiredTestClass(), State.class);
        if (state == null) throw new ParameterResolutionException("Rust-Java test application is not running");
        Class<?> type = parameter.getParameter().getType();
        if (type == ReactorTestClient.class) return state.client;
        if (type == RestApplication.RunningApplication.class) return state.application;
        if (type == ApplicationContext.class) return state.application.context();
        throw new ParameterResolutionException("Unsupported @ReactorTest parameter: " + type.getName());
    }

    private static Map<String, String> apply(String[] assignments) {
        Map<String, String> previous = new LinkedHashMap<>();
        for (String assignment : assignments) {
            int separator = assignment.indexOf('=');
            if (separator < 1) throw new IllegalArgumentException("Invalid @ReactorTest property: " + assignment);
            String key = assignment.substring(0, separator).trim();
            String value = assignment.substring(separator + 1).trim();
            previous.putIfAbsent(key, System.getProperty(key));
            System.setProperty(key, value);
        }
        return previous;
    }

    private static void restore(Map<String, String> previous) {
        for (Map.Entry<String, String> entry : previous.entrySet()) {
            if (entry.getValue() == null) System.clearProperty(entry.getKey());
            else System.setProperty(entry.getKey(), entry.getValue());
        }
    }

    private record State(
            RestApplication.RunningApplication application,
            ReactorTestClient client,
            Map<String, String> previous) implements AutoCloseable {
        @Override
        public void close() {
            try {
                application.close();
            } finally {
                restore(previous);
                NATIVE_SERVER_LOCK.unlock();
            }
        }
    }
}
