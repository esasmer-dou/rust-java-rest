package com.reactor.rust.scheduler;

import com.reactor.rust.app.ApplicationFeature;
import com.reactor.rust.app.ApplicationFeatureContext;
import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.logging.FrameworkLogger;
import com.reactor.rust.metrics.Metrics;

import java.time.Duration;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Starts only generated tasks and owns their bounded worker lifecycle. */
public final class SchedulerFeature implements ApplicationFeature {
    @Override
    public int order() {
        return 200;
    }

    @Override
    public void configure(ApplicationFeatureContext context) {
        int maxTasks = Math.max(1, PropertiesLoader.getInt("reactor.scheduler.max-tasks", 64));
        ScheduledTaskRegistry registry = context.application().beans().getBean(ScheduledTaskRegistry.class);
        List<ScheduledTaskDefinition> definitions = registry.drain(maxTasks);
        if (definitions.isEmpty()) return;
        Metrics.getInstance().setGauge("reactor_scheduler_tasks", definitions.size());
        if (!PropertiesLoader.getBoolean("reactor.scheduler.enabled", true)) {
            FrameworkLogger.info("[Scheduler] Disabled; generated tasks=" + definitions.size());
            return;
        }
        SchedulerRuntime runtime = SchedulerRuntime.start(definitions, lockProvider(definitions));
        context.manage(runtime);
    }

    private static ScheduledLockProvider lockProvider(List<ScheduledTaskDefinition> definitions) {
        boolean required = definitions.stream().anyMatch(task -> !task.lockName().isEmpty());
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = SchedulerFeature.class.getClassLoader();
        List<ScheduledLockProvider> providers = ServiceLoader.load(ScheduledLockProvider.class, loader).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        if (providers.size() > 1) {
            throw new IllegalStateException("Multiple ScheduledLockProvider implementations found: " + providers.size());
        }
        if (required && providers.isEmpty()) {
            throw new IllegalStateException(
                    "A generated @Scheduled task declares lockName, but no ScheduledLockProvider is installed");
        }
        return providers.isEmpty() ? null : providers.get(0);
    }

    private static final class SchedulerRuntime implements AutoCloseable {
        private final ScheduledThreadPoolExecutor executor;
        private final long shutdownTimeoutMs;

        private SchedulerRuntime(ScheduledThreadPoolExecutor executor, long shutdownTimeoutMs) {
            this.executor = executor;
            this.shutdownTimeoutMs = shutdownTimeoutMs;
        }

        private static SchedulerRuntime start(
                List<ScheduledTaskDefinition> definitions,
                ScheduledLockProvider lockProvider) {
            int threads = Math.max(1, PropertiesLoader.getInt("reactor.scheduler.threads", 1));
            threads = Math.min(threads, definitions.size());
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                    threads,
                    new SchedulerThreadFactory());
            executor.setRemoveOnCancelPolicy(true);
            executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
            for (ScheduledTaskDefinition source : definitions) {
                ResolvedTask task = resolve(source, lockProvider);
                Runnable guarded = task::run;
                if (source.mode() == com.reactor.rust.annotations.Scheduled.Mode.FIXED_RATE) {
                    executor.scheduleAtFixedRate(
                            guarded, task.initialDelayMs, task.intervalMs, TimeUnit.MILLISECONDS);
                } else {
                    executor.scheduleWithFixedDelay(
                            guarded, task.initialDelayMs, task.intervalMs, TimeUnit.MILLISECONDS);
                }
            }
            FrameworkLogger.info("[Scheduler] Started tasks=" + definitions.size() + " threads=" + threads);
            return new SchedulerRuntime(
                    executor,
                    Math.max(100L, PropertiesLoader.getLong("reactor.scheduler.shutdown-timeout-ms", 5_000L)));
        }

        private static ResolvedTask resolve(
                ScheduledTaskDefinition source,
                ScheduledLockProvider lockProvider) {
            long interval = source.intervalProperty().isEmpty()
                    ? source.intervalMs()
                    : PropertiesLoader.getLong(source.intervalProperty(), source.intervalMs());
            long initialDelay = source.initialDelayProperty().isEmpty()
                    ? source.initialDelayMs()
                    : PropertiesLoader.getLong(source.initialDelayProperty(), source.initialDelayMs());
            long lockAtMost = source.lockAtMostProperty().isEmpty()
                    ? source.lockAtMostMs()
                    : PropertiesLoader.getLong(source.lockAtMostProperty(), source.lockAtMostMs());
            if (interval <= 0L) {
                throw new IllegalArgumentException("Scheduled task " + source.name() + " interval must be > 0 ms");
            }
            if (initialDelay < 0L) {
                throw new IllegalArgumentException("Scheduled task " + source.name() + " initial delay must be >= 0 ms");
            }
            if (!source.lockName().isEmpty() && lockAtMost <= 0L) {
                throw new IllegalArgumentException("Scheduled task " + source.name() + " lockAtMost must be > 0 ms");
            }
            return new ResolvedTask(source, interval, initialDelay, lockAtMost, lockProvider);
        }

        @Override
        public void close() {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(shutdownTimeoutMs, TimeUnit.MILLISECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }

    private static final class ResolvedTask {
        private final ScheduledTaskDefinition source;
        private final long intervalMs;
        private final long initialDelayMs;
        private final long lockAtMostMs;
        private final ScheduledLockProvider lockProvider;
        private final AtomicBoolean running = new AtomicBoolean();

        private ResolvedTask(
                ScheduledTaskDefinition source,
                long intervalMs,
                long initialDelayMs,
                long lockAtMostMs,
                ScheduledLockProvider lockProvider) {
            this.source = source;
            this.intervalMs = intervalMs;
            this.initialDelayMs = initialDelayMs;
            this.lockAtMostMs = lockAtMostMs;
            this.lockProvider = lockProvider;
        }

        private void run() {
            Metrics metrics = Metrics.getInstance();
            if (!running.compareAndSet(false, true)) {
                metrics.increment("reactor_scheduler_overlap_skipped_total");
                return;
            }
            ScheduledLockProvider.LockLease lease = null;
            long started = System.nanoTime();
            try {
                if (!source.lockName().isEmpty()) {
                    lease = lockProvider.tryAcquire(source.lockName(), Duration.ofMillis(lockAtMostMs));
                    if (lease == null) {
                        metrics.increment("reactor_scheduler_lock_skipped_total");
                        return;
                    }
                }
                source.task().run();
                metrics.increment("reactor_scheduler_runs_total");
            } catch (Throwable failure) {
                metrics.increment("reactor_scheduler_failures_total");
                FrameworkLogger.error("[Scheduler] Task failed name=" + source.name()
                        + " type=" + failure.getClass().getName());
            } finally {
                if (lease != null) {
                    try {
                        lease.close();
                    } catch (RuntimeException closeFailure) {
                        metrics.increment("reactor_scheduler_lock_release_failures_total");
                    }
                }
                metrics.record("reactor_scheduler_duration_ms", (System.nanoTime() - started) / 1_000_000.0d);
                running.set(false);
            }
        }
    }

    private static final class SchedulerThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "reactor-scheduler-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        }
    }
}
