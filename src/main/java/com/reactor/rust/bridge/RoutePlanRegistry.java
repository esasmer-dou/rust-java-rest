package com.reactor.rust.bridge;

import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.logging.FrameworkLogger;
import com.reactor.rust.metrics.Metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Route optimizer visibility and production gate.
 *
 * <p>The registry is populated at startup by {@link RouteScanner}. It avoids
 * hot-path lookups and only exposes diagnostics/reporting for route execution
 * decisions.</p>
 */
public final class RoutePlanRegistry {

    private static final RoutePlanRegistry INSTANCE = new RoutePlanRegistry();

    private final CopyOnWriteArrayList<RouteExecutionPlan> plans = new CopyOnWriteArrayList<>();
    private volatile boolean runtimeMetricsEnabled;

    private RoutePlanRegistry() {
    }

    public static RoutePlanRegistry getInstance() {
        return INSTANCE;
    }

    public void clear() {
        plans.clear();
        runtimeMetricsEnabled = false;
    }

    public void configureFromProperties() {
        runtimeMetricsEnabled = PropertiesLoader.getBoolean(
                "reactor.optimizer.runtime-metrics-enabled",
                false
        );
    }

    public boolean runtimeMetricsEnabled() {
        return runtimeMetricsEnabled;
    }

    public void add(RouteExecutionPlan plan) {
        if (plan != null) {
            plans.add(plan);
        }
    }

    public List<RouteExecutionPlan> plans() {
        return Collections.unmodifiableList(new ArrayList<>(plans));
    }

    public void publishStartupMetrics() {
        Metrics metrics = Metrics.getInstance();
        metrics.setGauge("reactor.route.plan.total", plans.size());

        long optimized = 0;
        long legacy = 0;
        long compiled = 0;
        long exact = 0;
        EnumMap<RouteExecutionPlan.Strategy, Integer> byStrategy =
                new EnumMap<>(RouteExecutionPlan.Strategy.class);
        for (RouteExecutionPlan plan : plans) {
            if (plan.optimized()) {
                optimized++;
            } else {
                legacy++;
            }
            if (plan.compiledInvoker) {
                compiled++;
            }
            if (plan.exactInvoker) {
                exact++;
            }
            byStrategy.merge(plan.strategy, 1, Integer::sum);
        }

        metrics.setGauge("reactor.route.plan.optimized", optimized);
        metrics.setGauge("reactor.route.plan.legacy", legacy);
        metrics.setGauge("reactor.route.plan.compiled_invoker", compiled);
        metrics.setGauge("reactor.route.plan.exact_invoker", exact);
        for (java.util.Map.Entry<RouteExecutionPlan.Strategy, Integer> entry : byStrategy.entrySet()) {
            metrics.setGauge(
                    "reactor.route.plan.strategy." + entry.getKey().name().toLowerCase(Locale.ROOT),
                    entry.getValue()
            );
        }
    }

    public void logSummary() {
        boolean reportEnabled = PropertiesLoader.getBoolean("reactor.optimizer.report.enabled", true);
        if (!reportEnabled) {
            return;
        }

        long optimized = plans.stream().filter(RouteExecutionPlan::optimized).count();
        long legacy = plans.size() - optimized;
        long compiled = plans.stream().filter(plan -> plan.compiledInvoker).count();
        long exact = plans.stream().filter(plan -> plan.exactInvoker).count();
        FrameworkLogger.info("[reactor-route-plan] routes=" + plans.size()
                + " optimized=" + optimized
                + " legacy=" + legacy
                + " compiledInvoker=" + compiled
                + " exactInvoker=" + exact
                + " runtimeMetrics=" + runtimeMetricsEnabled);

        boolean verbose = PropertiesLoader.getBoolean("reactor.optimizer.report.verbose", true);
        if (!verbose) {
            return;
        }
        HandlerRegistry handlers = HandlerRegistry.getInstance();
        for (RouteExecutionPlan plan : plans) {
            FrameworkLogger.info(plan.toLogLine(handlers.getInvocationCount(plan.handlerId)));
        }
    }

    public void validateProductionGate() {
        String mode = PropertiesLoader.get("reactor.optimizer.mode", "observe");
        boolean strict = "strict".equalsIgnoreCase(mode);
        boolean failOnFallback = PropertiesLoader.getBoolean("reactor.optimizer.fail-on-fallback", false);
        boolean failOnLegacy = PropertiesLoader.getBoolean("reactor.optimizer.fail-on-legacy", false);
        boolean failOnImplicitRaw = PropertiesLoader.getBoolean(
                "reactor.optimizer.fail-on-implicit-raw-request-data",
                false
        );
        Set<String> requiredRoutes = parseRequiredRoutes(
                PropertiesLoader.get("reactor.optimizer.required-fast-routes", "")
        );

        if (!strict && !failOnFallback && !failOnLegacy && !failOnImplicitRaw && requiredRoutes.isEmpty()) {
            return;
        }

        List<String> violations = new ArrayList<>();
        for (RouteExecutionPlan plan : plans) {
            String routeKey = normalizeRouteKey(plan.routeKey());
            boolean required = requiredRoutes.contains(routeKey);

            if ((strict || failOnFallback || required) && !plan.optimized()) {
                violations.add(plan.routeKey() + " is not optimized; strategy=" + plan.strategy
                        + " reason=" + plan.reason);
            }
            if (failOnLegacy && plan.legacyV4) {
                violations.add(plan.routeKey() + " uses legacy V4 handler signature");
            }
            if (failOnImplicitRaw && plan.implicitRawMetadata()) {
                violations.add(plan.routeKey()
                        + " uses Direct V5 without @RawRequestData; raw path/query/header strings stay enabled");
            }
        }

        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "Route optimizer production gate failed: " + String.join("; ", violations)
            );
        }
    }

    public String toJson() {
        HandlerRegistry handlers = HandlerRegistry.getInstance();
        StringBuilder json = new StringBuilder(512 + plans.size() * 256);
        long optimized = plans.stream().filter(RouteExecutionPlan::optimized).count();
        long compiled = plans.stream().filter(plan -> plan.compiledInvoker).count();
        long exact = plans.stream().filter(plan -> plan.exactInvoker).count();
        json.append('{');
        json.append("\"runtime_metrics_enabled\":").append(runtimeMetricsEnabled).append(',');
        json.append("\"total\":").append(plans.size()).append(',');
        json.append("\"optimized\":").append(optimized).append(',');
        json.append("\"legacy\":").append(plans.size() - optimized).append(',');
        json.append("\"compiled_invoker\":").append(compiled).append(',');
        json.append("\"exact_invoker\":").append(exact).append(',');
        json.append("\"routes\":[");
        for (int i = 0; i < plans.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            RouteExecutionPlan plan = plans.get(i);
            json.append(plan.toJson(handlers.getInvocationCount(plan.handlerId)));
        }
        json.append("]}");
        return json.toString();
    }

    private static Set<String> parseRequiredRoutes(String value) {
        if (value == null || value.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> routes = new HashSet<>();
        for (String token : value.split(",")) {
            String route = token.trim();
            if (!route.isEmpty()) {
                routes.add(normalizeRouteKey(route));
            }
        }
        return routes;
    }

    private static String normalizeRouteKey(String route) {
        return route.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

}
