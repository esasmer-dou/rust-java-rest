package com.reactor.rust.bridge;

import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.logging.FrameworkLogger;
import com.reactor.rust.metrics.Metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Route optimizer visibility and production gate.
 *
 * <p>The registry is populated at startup by {@link RouteScanner}. It avoids
 * hot-path lookups and only exposes diagnostics/reporting for route execution
 * decisions.</p>
 */
public final class RoutePlanRegistry {

    private static final RoutePlanRegistry INSTANCE = new RoutePlanRegistry();

    private final List<RouteExecutionPlan> buildingPlans = new ArrayList<>();
    private volatile List<RouteExecutionPlan> plans = buildingPlans;
    private volatile boolean runtimeMetricsEnabled;

    private RoutePlanRegistry() {
    }

    public static RoutePlanRegistry getInstance() {
        return INSTANCE;
    }

    public synchronized void clear() {
        buildingPlans.clear();
        plans = buildingPlans;
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

    public synchronized void add(RouteExecutionPlan plan) {
        if (plan != null) {
            buildingPlans.add(plan);
        }
    }

    /** Publishes one immutable route plan after startup discovery is complete. */
    public synchronized void freeze() {
        plans = List.copyOf(buildingPlans);
        buildingPlans.clear();
    }

    public List<RouteExecutionPlan> plans() {
        return plans;
    }

    public void publishStartupMetrics() {
        Metrics metrics = Metrics.getInstance();
        metrics.setGauge("reactor.route.plan.total", plans.size());

        long optimized = 0;
        long legacy = 0;
        long productionOptimized = 0;
        long productionLegacy = 0;
        long benchmarkOptimized = 0;
        long benchmarkLegacy = 0;
        long compiled = 0;
        long exact = 0;
        long heavyJsonObjectGraph = 0;
        long benchmarkOnly = 0;
        long benchmarkHeavyJsonObjectGraph = 0;
        EnumMap<RouteExecutionPlan.Strategy, Integer> byStrategy =
                new EnumMap<>(RouteExecutionPlan.Strategy.class);
        Map<String, Integer> byWorkload = new HashMap<>();
        for (RouteExecutionPlan plan : plans) {
            if (plan.optimized()) {
                optimized++;
            } else {
                legacy++;
            }
            if (plan.productionRoute() && plan.optimized()) {
                productionOptimized++;
            } else if (plan.productionRoute()) {
                productionLegacy++;
            } else if (plan.optimized()) {
                benchmarkOptimized++;
            } else {
                benchmarkLegacy++;
            }
            if (plan.compiledInvoker) {
                compiled++;
            }
            if (plan.exactInvoker) {
                exact++;
            }
            if (plan.benchmarkOnly) {
                benchmarkOnly++;
            }
            if (plan.heavyJsonObjectGraph() && plan.productionRoute()) {
                heavyJsonObjectGraph++;
            }
            if (plan.heavyJsonObjectGraph() && plan.benchmarkOnly) {
                benchmarkHeavyJsonObjectGraph++;
            }
            byStrategy.merge(plan.strategy, 1, Integer::sum);
            byWorkload.merge(plan.workload, 1, Integer::sum);
        }

        metrics.setGauge("reactor.route.plan.optimized", optimized);
        metrics.setGauge("reactor.route.plan.legacy", legacy);
        metrics.setGauge("reactor.route.plan.production", plans.size() - benchmarkOnly);
        metrics.setGauge("reactor.route.plan.production_optimized", productionOptimized);
        metrics.setGauge("reactor.route.plan.production_legacy", productionLegacy);
        metrics.setGauge("reactor.route.plan.benchmark_optimized", benchmarkOptimized);
        metrics.setGauge("reactor.route.plan.benchmark_legacy", benchmarkLegacy);
        metrics.setGauge("reactor.route.plan.compiled_invoker", compiled);
        metrics.setGauge("reactor.route.plan.exact_invoker", exact);
        metrics.setGauge("reactor.route.plan.heavy_json_object_graph", heavyJsonObjectGraph);
        metrics.setGauge("reactor.route.plan.benchmark_only", benchmarkOnly);
        metrics.setGauge("reactor.route.plan.benchmark_heavy_json_object_graph", benchmarkHeavyJsonObjectGraph);
        for (java.util.Map.Entry<RouteExecutionPlan.Strategy, Integer> entry : byStrategy.entrySet()) {
            metrics.setGauge(
                    "reactor.route.plan.strategy." + entry.getKey().name().toLowerCase(Locale.ROOT),
                    entry.getValue()
            );
        }
        for (Map.Entry<String, Integer> entry : byWorkload.entrySet()) {
            metrics.setGauge(
                    "reactor.route.plan.workload." + entry.getKey().toLowerCase(Locale.ROOT),
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
        long productionOptimized = plans.stream()
                .filter(plan -> plan.productionRoute() && plan.optimized())
                .count();
        long productionLegacy = plans.stream()
                .filter(plan -> plan.productionRoute() && !plan.optimized())
                .count();
        long benchmarkOptimized = plans.stream()
                .filter(plan -> plan.benchmarkOnly && plan.optimized())
                .count();
        long benchmarkLegacy = plans.stream()
                .filter(plan -> plan.benchmarkOnly && !plan.optimized())
                .count();
        long compiled = plans.stream().filter(plan -> plan.compiledInvoker).count();
        long exact = plans.stream().filter(plan -> plan.exactInvoker).count();
        long benchmarkOnly = plans.stream().filter(plan -> plan.benchmarkOnly).count();
        long heavyJsonObjectGraph = plans.stream()
                .filter(plan -> plan.productionRoute() && plan.heavyJsonObjectGraph())
                .count();
        long benchmarkHeavyJsonObjectGraph = plans.stream()
                .filter(plan -> plan.benchmarkOnly && plan.heavyJsonObjectGraph())
                .count();
        FrameworkLogger.info("[reactor-route-plan] routes=" + plans.size()
                + " optimized=" + optimized
                + " legacy=" + legacy
                + " productionOptimized=" + productionOptimized
                + " productionLegacy=" + productionLegacy
                + " benchmarkOptimized=" + benchmarkOptimized
                + " benchmarkLegacy=" + benchmarkLegacy
                + " compiledInvoker=" + compiled
                + " exactInvoker=" + exact
                + " heavyJsonObjectGraph=" + heavyJsonObjectGraph
                + " benchmarkOnly=" + benchmarkOnly
                + " benchmarkHeavyJsonObjectGraph=" + benchmarkHeavyJsonObjectGraph
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
        boolean failOnHeavyJsonObjectGraph = PropertiesLoader.getBoolean(
                "reactor.optimizer.fail-on-heavy-json-object-graph",
                false
        );
        boolean failOnBenchmarkOnly = PropertiesLoader.getBoolean(
                "reactor.optimizer.fail-on-benchmark-only-routes",
                false
        );
        Set<String> requiredRoutes = parseRequiredRoutes(
                PropertiesLoader.get("reactor.optimizer.required-fast-routes", "")
        );

        if (!strict
                && !failOnFallback
                && !failOnLegacy
                && !failOnImplicitRaw
                && !failOnHeavyJsonObjectGraph
                && !failOnBenchmarkOnly
                && requiredRoutes.isEmpty()) {
            return;
        }

        List<String> violations = new ArrayList<>();
        for (RouteExecutionPlan plan : plans) {
            String routeKey = normalizeRouteKey(plan.routeKey());
            boolean required = requiredRoutes.contains(routeKey);
            boolean productionGateRoute = plan.productionRoute() || required;

            if (failOnBenchmarkOnly && plan.benchmarkOnly) {
                violations.add(plan.routeKey()
                        + " is marked benchmark-only and must not be present in this production gate");
            }
            if (productionGateRoute && (strict || failOnFallback || required) && !plan.optimized()) {
                violations.add(plan.routeKey() + " is not optimized; strategy=" + plan.strategy
                        + " reason=" + plan.reason);
            }
            if (productionGateRoute && failOnLegacy && plan.legacyV4) {
                violations.add(plan.routeKey() + " uses legacy V4 handler signature");
            }
            if (productionGateRoute && failOnImplicitRaw && plan.implicitRawMetadata()) {
                violations.add(plan.routeKey()
                        + " uses Direct V5 without @RawRequestData; raw path/query/header strings stay enabled");
            }
            if (productionGateRoute && failOnHeavyJsonObjectGraph && plan.heavyJsonObjectGraph()) {
                violations.add(plan.routeKey()
                        + " is marked HEAVY_JSON but still uses object-graph or legacy serialization path; "
                        + "strategy=" + plan.strategy + " reason=" + plan.reason
                        + ". Use direct buffer writer, JsonProducerResponse, RawResponse, or native static response.");
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
        long benchmarkOnly = plans.stream().filter(plan -> plan.benchmarkOnly).count();
        long productionOptimized = plans.stream()
                .filter(plan -> plan.productionRoute() && plan.optimized())
                .count();
        long productionLegacy = plans.stream()
                .filter(plan -> plan.productionRoute() && !plan.optimized())
                .count();
        long benchmarkOptimized = plans.stream()
                .filter(plan -> plan.benchmarkOnly && plan.optimized())
                .count();
        long benchmarkLegacy = plans.stream()
                .filter(plan -> plan.benchmarkOnly && !plan.optimized())
                .count();
        long heavyJsonObjectGraph = plans.stream()
                .filter(plan -> plan.productionRoute() && plan.heavyJsonObjectGraph())
                .count();
        long benchmarkHeavyJsonObjectGraph = plans.stream()
                .filter(plan -> plan.benchmarkOnly && plan.heavyJsonObjectGraph())
                .count();
        json.append('{');
        json.append("\"runtime_metrics_enabled\":").append(runtimeMetricsEnabled).append(',');
        json.append("\"total\":").append(plans.size()).append(',');
        json.append("\"production_routes\":").append(plans.size() - benchmarkOnly).append(',');
        json.append("\"benchmark_only\":").append(benchmarkOnly).append(',');
        json.append("\"optimized\":").append(optimized).append(',');
        json.append("\"legacy\":").append(plans.size() - optimized).append(',');
        json.append("\"production_optimized\":").append(productionOptimized).append(',');
        json.append("\"production_legacy\":").append(productionLegacy).append(',');
        json.append("\"benchmark_optimized\":").append(benchmarkOptimized).append(',');
        json.append("\"benchmark_legacy\":").append(benchmarkLegacy).append(',');
        json.append("\"compiled_invoker\":").append(compiled).append(',');
        json.append("\"exact_invoker\":").append(exact).append(',');
        json.append("\"heavy_json_object_graph\":").append(heavyJsonObjectGraph).append(',');
        json.append("\"benchmark_heavy_json_object_graph\":").append(benchmarkHeavyJsonObjectGraph).append(',');
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
