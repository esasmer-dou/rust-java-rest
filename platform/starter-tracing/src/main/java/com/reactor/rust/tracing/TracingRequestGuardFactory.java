package com.reactor.rust.tracing;

import com.reactor.rust.annotations.DirectPathBoolean;
import com.reactor.rust.annotations.DirectPathDouble;
import com.reactor.rust.annotations.DirectPathInt;
import com.reactor.rust.annotations.DirectPathLong;
import com.reactor.rust.annotations.DirectPathShort;
import com.reactor.rust.annotations.DirectQueryBoolean;
import com.reactor.rust.annotations.DirectQueryDouble;
import com.reactor.rust.annotations.DirectQueryInt;
import com.reactor.rust.annotations.DirectQueryLong;
import com.reactor.rust.annotations.DirectQueryShort;
import com.reactor.rust.annotations.NativeStaticFileRoute;
import com.reactor.rust.annotations.NativeStaticRoute;
import com.reactor.rust.bridge.GeneratedPrimitiveBindings;
import com.reactor.rust.bridge.RequestGuard;
import com.reactor.rust.bridge.RequestGuardFactory;
import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.metrics.Metrics;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.ThreadLocalRandom;

/** Adds W3C propagation only to selected Java routes; disabled routes retain their original hot path. */
public final class TracingRequestGuardFactory implements RequestGuardFactory {
    private final boolean enabled;
    private final boolean annotatedOnly;
    private final double sampleRatio;
    private final TraceExporter exporter;

    public TracingRequestGuardFactory() {
        this.enabled = PropertiesLoader.getBoolean("reactor.tracing.enabled", false);
        this.annotatedOnly = enabled && PropertiesLoader.getBoolean("reactor.tracing.annotated-only", false);
        this.sampleRatio = enabled ? sampleRatio() : 0.0d;
        this.exporter = enabled ? loadExporter() : null;
    }

    @Override
    public int order() {
        return 0;
    }

    @Override
    public RequestGuard create(Class<?> owner, Method method) {
        if (!enabled) return null;
        Traced annotation = method.getAnnotation(Traced.class);
        if (annotation == null) annotation = owner.getAnnotation(Traced.class);
        if (annotatedOnly && annotation == null) return null;
        if (annotation == null && specialized(method)) return null;
        String name = annotation != null && !annotation.value().isBlank()
                ? annotation.value().trim()
                : owner.getSimpleName() + '.' + method.getName();
        return new TracingGuard(name, sampleRatio, exporter);
    }

    private static boolean specialized(Method method) {
        if (GeneratedPrimitiveBindings.find(method) != null
                || method.isAnnotationPresent(NativeStaticRoute.class)
                || method.isAnnotationPresent(NativeStaticFileRoute.class)
                || method.isAnnotationPresent(DirectQueryInt.class)
                || method.isAnnotationPresent(DirectQueryLong.class)
                || method.isAnnotationPresent(DirectQueryBoolean.class)
                || method.isAnnotationPresent(DirectQueryDouble.class)
                || method.isAnnotationPresent(DirectQueryShort.class)
                || method.isAnnotationPresent(DirectPathInt.class)
                || method.isAnnotationPresent(DirectPathLong.class)
                || method.isAnnotationPresent(DirectPathBoolean.class)
                || method.isAnnotationPresent(DirectPathDouble.class)
                || method.isAnnotationPresent(DirectPathShort.class)) return true;
        Class<?>[] parameters = method.getParameterTypes();
        return parameters.length >= 2
                && parameters[0] == ByteBuffer.class
                && parameters[1] == int.class
                && parameters.length <= 3;
    }

    private static double sampleRatio() {
        double configured = PropertiesLoader.getDouble("reactor.tracing.sample-ratio", 0.01d);
        if (!Double.isFinite(configured) || configured < 0.0d || configured > 1.0d) {
            throw new IllegalArgumentException("reactor.tracing.sample-ratio must be between 0.0 and 1.0");
        }
        return configured;
    }

    private static TraceExporter loadExporter() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = TracingRequestGuardFactory.class.getClassLoader();
        List<TraceExporter> exporters = ServiceLoader.load(TraceExporter.class, loader).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        if (exporters.size() > 1) {
            throw new IllegalStateException("Multiple TraceExporter implementations found: " + exporters.size());
        }
        return exporters.isEmpty() ? new MetricsExporter() : exporters.get(0);
    }

    private static final class TracingGuard implements RequestGuard {
        private final String name;
        private final double sampleRatio;
        private final TraceExporter exporter;
        private final ThreadLocal<Invocation> current = new ThreadLocal<>();

        private TracingGuard(String name, double sampleRatio, TraceExporter exporter) {
            this.name = name;
            this.sampleRatio = sampleRatio;
            this.exporter = exporter;
        }

        @Override
        public void before(com.reactor.rust.bridge.RequestGuardContext request) {
            Parent parent = Parent.parse(request.header("traceparent"));
            boolean sampled = parent == null
                    ? ThreadLocalRandom.current().nextDouble() < sampleRatio
                    : parent.sampled;
            TraceContext context = new TraceContext(
                    parent == null ? Hex.traceId() : parent.traceId,
                    Hex.spanId(),
                    sampled);
            TraceContext.set(context);
            current.set(new Invocation(context, System.nanoTime()));
        }

        @Override
        public void after() {
            after(null);
        }

        @Override
        public void after(Throwable failure) {
            Invocation invocation = current.get();
            current.remove();
            TraceContext.clear();
            export(invocation, failure);
        }

        @Override
        public <T> java.util.concurrent.CompletionStage<T> afterAsync(
                java.util.concurrent.CompletionStage<T> stage) {
            Invocation invocation = current.get();
            current.remove();
            TraceContext.clear();
            if (invocation == null || !invocation.context.sampled()) return stage;
            return stage.whenComplete((ignored, failure) -> export(invocation, unwrap(failure)));
        }

        private void export(Invocation invocation, Throwable failure) {
            if (invocation == null || !invocation.context.sampled()) return;
            exporter.export(new TraceSpan(
                    name,
                    invocation.context.traceId(),
                    invocation.context.spanId(),
                    Math.max(0L, System.nanoTime() - invocation.startedNanos),
                    true,
                    failure == null,
                    failure == null ? "" : failure.getClass().getName()));
        }

        private static Throwable unwrap(Throwable failure) {
            if (failure instanceof java.util.concurrent.CompletionException completion
                    && completion.getCause() != null) return completion.getCause();
            return failure;
        }
    }

    private record Invocation(TraceContext context, long startedNanos) {}

    private record Parent(String traceId, boolean sampled) {
        private static Parent parse(String header) {
            if (header == null || header.length() != 55
                    || header.charAt(2) != '-' || header.charAt(35) != '-' || header.charAt(52) != '-') return null;
            String version = header.substring(0, 2);
            String traceId = header.substring(3, 35);
            String parentId = header.substring(36, 52);
            String flags = header.substring(53, 55);
            if ("ff".equalsIgnoreCase(version)
                    || !Hex.valid(traceId, 32)
                    || !Hex.valid(parentId, 16)
                    || !Hex.valid(flags, 2)
                    || Hex.zero(traceId)
                    || Hex.zero(parentId)) return null;
            return new Parent(traceId.toLowerCase(java.util.Locale.ROOT), (Integer.parseInt(flags, 16) & 1) == 1);
        }
    }

    private static final class Hex {
        private static final char[] DIGITS = "0123456789abcdef".toCharArray();

        private static String traceId() {
            long high;
            long low;
            do {
                high = ThreadLocalRandom.current().nextLong();
                low = ThreadLocalRandom.current().nextLong();
            } while (high == 0L && low == 0L);
            char[] output = new char[32];
            write(output, 0, high);
            write(output, 16, low);
            return new String(output);
        }

        private static String spanId() {
            long value;
            do value = ThreadLocalRandom.current().nextLong(); while (value == 0L);
            char[] output = new char[16];
            write(output, 0, value);
            return new String(output);
        }

        private static void write(char[] output, int offset, long value) {
            for (int index = 15; index >= 0; index--) {
                output[offset + index] = DIGITS[(int) (value & 0xfL)];
                value >>>= 4;
            }
        }

        private static boolean valid(String value, int length) {
            if (value.length() != length) return false;
            for (int index = 0; index < length; index++) {
                char current = value.charAt(index);
                if (!((current >= '0' && current <= '9')
                        || (current >= 'a' && current <= 'f')
                        || (current >= 'A' && current <= 'F'))) return false;
            }
            return true;
        }

        private static boolean zero(String value) {
            for (int index = 0; index < value.length(); index++) {
                if (value.charAt(index) != '0') return false;
            }
            return true;
        }
    }

    private static final class MetricsExporter implements TraceExporter {
        @Override
        public void export(TraceSpan span) {
            Metrics metrics = Metrics.getInstance();
            metrics.increment("reactor_trace_handler_spans_total");
            if (!span.success()) metrics.increment("reactor_trace_handler_failures_total");
            metrics.record("reactor_trace_handler_duration_ms", span.durationNanos() / 1_000_000.0d);
        }
    }
}
