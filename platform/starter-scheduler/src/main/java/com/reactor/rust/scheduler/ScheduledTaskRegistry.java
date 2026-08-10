package com.reactor.rust.scheduler;

import com.reactor.rust.annotations.Scheduled;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Application-scoped registry populated only by generated scheduled-task factories. */
public final class ScheduledTaskRegistry {
    private final ArrayList<ScheduledTaskDefinition> tasks = new ArrayList<>();
    private boolean drained;

    public synchronized void register(
            String name,
            Runnable task,
            Scheduled.Mode mode,
            long intervalMs,
            String intervalProperty,
            long initialDelayMs,
            String initialDelayProperty,
            String lockName,
            long lockAtMostMs,
            String lockAtMostProperty) {
        if (drained) throw new IllegalStateException("Scheduled task registry is already active");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(mode, "mode");
        for (ScheduledTaskDefinition existing : tasks) {
            if (existing.name().equals(name)) {
                throw new IllegalStateException("Duplicate scheduled task name: " + name);
            }
        }
        tasks.add(new ScheduledTaskDefinition(
                name,
                task,
                mode,
                intervalMs,
                normalize(intervalProperty),
                initialDelayMs,
                normalize(initialDelayProperty),
                normalize(lockName),
                lockAtMostMs,
                normalize(lockAtMostProperty)));
    }

    synchronized List<ScheduledTaskDefinition> drain(int maximum) {
        if (drained) return List.of();
        if (tasks.size() > maximum) {
            throw new IllegalStateException(
                    "Generated scheduled task count " + tasks.size()
                            + " exceeds reactor.scheduler.max-tasks=" + maximum);
        }
        drained = true;
        List<ScheduledTaskDefinition> snapshot = List.copyOf(tasks);
        tasks.clear();
        return snapshot;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
