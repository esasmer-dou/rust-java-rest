package com.reactor.rust.scheduler;

import com.reactor.rust.annotations.Scheduled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScheduledTaskRegistryTest {
    @Test
    void rejectsDuplicateNamesAndDrainsOnce() {
        ScheduledTaskRegistry registry = new ScheduledTaskRegistry();
        registry.register("catalog-refresh", () -> {}, Scheduled.Mode.FIXED_DELAY,
                1_000L, "", 0L, "", "", 0L, "");

        assertThrows(IllegalStateException.class, () -> registry.register(
                "catalog-refresh", () -> {}, Scheduled.Mode.FIXED_DELAY,
                1_000L, "", 0L, "", "", 0L, ""));
        assertEquals(1, registry.drain(4).size());
        assertEquals(0, registry.drain(4).size());
    }

    @Test
    void enforcesBoundedTaskCount() {
        ScheduledTaskRegistry registry = new ScheduledTaskRegistry();
        registry.register("one", () -> {}, Scheduled.Mode.FIXED_RATE,
                1_000L, "", 0L, "", "", 0L, "");
        registry.register("two", () -> {}, Scheduled.Mode.FIXED_RATE,
                1_000L, "", 0L, "", "", 0L, "");

        assertThrows(IllegalStateException.class, () -> registry.drain(1));
    }
}
