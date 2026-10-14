package com.reactor.rust.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VerifyNativeAbiMojoTest {

    @Test
    void cacheRuntimeVerifiesOnlyItsRedisContract() {
        Properties provenance = provenance(29, 7, 6, 4);

        assertDoesNotThrow(() -> VerifyNativeAbiMojo.verifyProvenance(
                "java-rust-cache", provenance, 29, 7, 6, 6));
    }

    @Test
    void cacheRuntimeRejectsRedisAbiMismatch() {
        Properties provenance = provenance(29, 7, 5, 4);

        assertThrows(
                MojoExecutionException.class,
                () -> VerifyNativeAbiMojo.verifyProvenance(
                        "java-rust-cache", provenance, 29, 7, 6, 6));
    }

    @Test
    void restRuntimeVerifiesEverySharedContract() {
        Properties provenance = provenance(29, 7, 6, 4);

        assertThrows(
                MojoExecutionException.class,
                () -> VerifyNativeAbiMojo.verifyProvenance(
                        "rust-java-rest", provenance, 29, 7, 6, 6));
    }

    private static Properties provenance(int restAbi, int dubboAbi, int redisAbi, int glowrootAbi) {
        Properties values = new Properties();
        values.setProperty("rest.abi", Integer.toString(restAbi));
        values.setProperty("dubbo.abi", Integer.toString(dubboAbi));
        values.setProperty("redis.abi", Integer.toString(redisAbi));
        values.setProperty("glowroot.abi", Integer.toString(glowrootAbi));
        return values;
    }
}
