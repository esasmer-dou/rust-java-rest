package com.reactor.rust.scheduler;

import com.reactor.rust.annotations.Scheduled;

record ScheduledTaskDefinition(
        String name,
        Runnable task,
        Scheduled.Mode mode,
        long intervalMs,
        String intervalProperty,
        long initialDelayMs,
        String initialDelayProperty,
        String lockName,
        long lockAtMostMs,
        String lockAtMostProperty) {}
