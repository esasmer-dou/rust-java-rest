package com.reactor.rust.startup;

import com.reactor.rust.metrics.Metrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class InstantOnCheckpointTest {

    @AfterEach
    void tearDown() {
        System.clearProperty("reactor.instanton.checkpoint.enabled");
        Metrics.getInstance().reset();
    }

    @Test
    void disabledCheckpointIsNoOp() {
        System.setProperty("reactor.instanton.checkpoint.enabled", "false");

        assertFalse(InstantOnCheckpoint.checkpointIfEnabled());
        assertFalse(InstantOnCheckpoint.isRestored());
    }
}
