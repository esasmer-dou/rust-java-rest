package com.reactor.rust.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppCdsProcessRunnerTest {
    @TempDir
    Path temp;

    @AfterEach
    void clearInterrupt() { Thread.interrupted(); }

    @Test
    void redirectsCombinedOutputDirectlyToFileClosesStdinAndDoesNotUseAShell() throws Exception {
        FakeProcess child = new FakeProcess(0, true);
        Path log = temp.resolve("training output.log");
        List<String> command = List.of("JDK with spaces/bin/java", "example.OfflineTraining", "path with spaces", "literal;value");
        AppCdsProcessRunner runner = new AppCdsProcessRunner(builder -> {
            assertEquals(command, builder.command());
            assertEquals(temp.toFile(), builder.directory());
            assertTrue(builder.redirectErrorStream());
            assertEquals(ProcessBuilder.Redirect.Type.WRITE, builder.redirectOutput().type());
            assertEquals(log.toFile(), builder.redirectOutput().file());
            for (String key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
                    "IBM_JAVA_OPTIONS", "OPENJ9_JAVA_OPTIONS", "CLASSPATH")) {
                assertFalse(builder.environment().containsKey(key));
            }
            return child;
        });

        runner.run(command, temp, log, 19);

        assertTrue(child.stdinClosed);
        assertEquals(List.of(19L), child.waits);
        assertEquals(0, child.destroyed);
        assertEquals(0, child.forced);
    }

    @Test
    void nonzeroExitFailsWithLogLocationWithoutRetainingOutput() {
        FakeProcess child = new FakeProcess(7, true);
        MojoExecutionException failure = assertThrows(MojoExecutionException.class,
                () -> run(child));
        assertTrue(failure.getMessage().contains("exit code 7"));
        assertTrue(failure.getMessage().contains("training.log"));
        assertEquals(0, child.forced);
    }

    @Test
    void timeoutDestroysOwnedChildThenWaitsForGracefulTermination() {
        FakeProcess child = new FakeProcess(0, false, true);
        MojoExecutionException failure = assertThrows(MojoExecutionException.class, () -> run(child));

        assertTrue(failure.getMessage().contains("timed out after 3 seconds"));
        assertEquals(List.of(3L, 2L), child.waits);
        assertEquals(1, child.destroyed);
        assertEquals(0, child.forced);
    }

    @Test
    void timeoutEscalatesOnlyOwnedChildAndNeverEnumeratesOtherProcesses() {
        FakeProcess child = new FakeProcess(0, false, false, true);
        FakeProcess unrelated = new FakeProcess(0, false);
        assertThrows(MojoExecutionException.class, () -> run(child));

        assertEquals(List.of(3L, 2L, 2L), child.waits);
        assertEquals(1, child.destroyed);
        assertEquals(1, child.forced);
        assertEquals(0, unrelated.destroyed);
        assertEquals(0, unrelated.forced);
    }

    @Test
    void unconfirmedForcedTerminationStillFailsAndDoesNotWaitForever() {
        FakeProcess child = new FakeProcess(0, false, false, false);
        MojoExecutionException failure = assertThrows(MojoExecutionException.class, () -> run(child));
        assertTrue(failure.getMessage().contains("forced termination unconfirmed"));
        assertEquals(List.of(3L, 2L, 2L), child.waits);
        assertTrue(child.forced >= 1);
    }

    @Test
    void interruptionRestoresFlagAndForciblyStopsOnlyOwnedChild() {
        FakeProcess child = new FakeProcess(0, false);
        child.interrupt = true;
        MojoExecutionException failure = assertThrows(MojoExecutionException.class, () -> run(child));

        assertTrue(failure.getMessage().contains("interrupted"));
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(1, child.forced);
    }

    @Test
    void startFailureIsReportedWithoutAnyProcessEnumeration() {
        AppCdsProcessRunner runner = new AppCdsProcessRunner(builder -> { throw new IOException("start rejected"); });
        assertTrue(assertThrows(MojoExecutionException.class,
                () -> runner.run(List.of("java"), temp, temp.resolve("training.log"), 3))
                .getMessage().contains("Cannot run AppCDS child"));
    }

    @Test
    void stdinCloseFailureStillTerminatesOwnedChild() {
        FakeProcess child = new FakeProcess(0, false, true);
        child.failClose = true;
        assertThrows(MojoExecutionException.class, () -> run(child));
        assertEquals(1, child.forced);
        assertEquals(List.of(2L, 2L), child.waits);
        assertFalse(child.isAlive());
    }

    @Test
    void interruptedWaitReapsTheWriterBeforeRestoringInterrupt() {
        FakeProcess child = new FakeProcess(0, false, true);
        child.interruptOnce = true;
        assertThrows(MojoExecutionException.class, () -> run(child));
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(List.of(3L, 2L, 2L), child.waits);
        assertFalse(child.isAlive());
        assertEquals(1, child.forced);
    }

    private void run(FakeProcess child) throws MojoExecutionException {
        new AppCdsProcessRunner(builder -> child).run(List.of("java"), temp, temp.resolve("training.log"), 3);
    }

    private static final class FakeProcess extends Process {
        private final int exit;
        private final boolean[] completions;
        private final List<Long> waits = new ArrayList<>();
        private int waitIndex;
        private boolean alive = true;
        private boolean stdinClosed;
        private boolean interrupt;
        private boolean interruptOnce;
        private boolean failClose;
        private int destroyed;
        private int forced;

        private FakeProcess(int exit, boolean... completions) {
            this.exit = exit;
            this.completions = completions;
        }

        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override
                public void write(int value) { throw new AssertionError("Do not supply interactive stdin"); }
                @Override
                public void close() throws IOException {
                    stdinClosed = true;
                    if (failClose) throw new IOException("stdin unavailable");
                }
            };
        }

        @Override
        public InputStream getInputStream() { throw new AssertionError("Output must go directly to a target file"); }
        @Override
        public InputStream getErrorStream() { throw new AssertionError("Error output must go directly to a target file"); }
        @Override
        public int waitFor() { throw new AssertionError("Unbounded wait prohibited"); }
        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            assertEquals(TimeUnit.SECONDS, unit);
            waits.add(timeout);
            if (interrupt) throw new InterruptedException("test interrupt");
            if (interruptOnce) {
                interruptOnce = false;
                throw new InterruptedException("test single interrupt");
            }
            boolean complete = completions[waitIndex++];
            if (complete) alive = false;
            return complete;
        }
        @Override
        public int exitValue() {
            if (alive) throw new IllegalThreadStateException();
            return exit;
        }
        @Override
        public boolean isAlive() { return alive; }
        @Override
        public void destroy() { destroyed++; }
        @Override
        public Process destroyForcibly() { forced++; return this; }
    }
}
