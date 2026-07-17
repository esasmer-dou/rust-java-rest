package com.reactor.rust.health;

import com.reactor.rust.http.HttpStatus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthStarterTest {

    @Test
    void readinessReturnsServiceUnavailableWithoutLeakingFailureDetails() {
        HealthEndpoint endpoint = HealthStarter.application("sample")
                .required("database", 100, () -> {
                    throw new IllegalStateException("password=secret");
                })
                .build();

        var response = endpoint.readiness();
        String json = new String(response.getBody().getBody(), StandardCharsets.UTF_8);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatus());
        assertTrue(json.contains("\"status\":\"DOWN\""));
        assertTrue(json.contains("\"name\":\"database\""));
        assertFalse(json.contains("secret"));
    }

    @Test
    void optionalDependencyDoesNotBlockReadiness() {
        HealthEndpoint endpoint = HealthStarter.application("sample")
                .optional("telemetry", 100, () -> false)
                .build();

        assertEquals(HttpStatus.OK, endpoint.readiness().getStatus());
    }

    @Test
    void rejectsDependencyNamesThatWouldCollideInMetrics() {
        HealthStarter.Builder builder = HealthStarter.application("sample")
                .required("customer-db", 100, () -> true);

        assertThrows(
                IllegalArgumentException.class,
                () -> builder.required("customer db", 100, () -> true));
        assertThrows(
                IllegalArgumentException.class,
                () -> HealthStarter.application("sample").required("---", 100, () -> true));
    }
}
