package com.reactor.rust.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/** Parent explicitly selects these only in the matching EmaTech Java 21 image. */
@EnabledIfEnvironmentVariable(named = "REACTOR_TEST_CLUSTER", matches = "EmaTech")
class AppCdsArchiveIntegrationTest {
    @TempDir Path temp;

    @Test
    @EnabledIfSystemProperty(named = "reactor.appcds.integration.vm", matches = "hotspot")
    void generatesLoadsApplicationClassAndRejectsIncompatibleArchivesWithBoundedTraining() throws Exception {
        AppCdsEmaTechHarness.generate(temp.resolve("hotspot"));
    }

    @Test
    @EnabledIfSystemProperty(named = "reactor.appcds.integration.vm", matches = "openj9")
    void actualOpenJ9RejectsArchiveTrainingBeforeProjectAndProcessAccess() throws Exception {
        AppCdsEmaTechHarness.rejectOpenJ9(temp.resolve("openj9"));
    }
}
