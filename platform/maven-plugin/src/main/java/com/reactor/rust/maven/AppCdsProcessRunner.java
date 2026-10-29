package com.reactor.rust.maven;

import org.apache.maven.plugin.MojoExecutionException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs only the directly owned child, with disk-backed logs and bounded shutdown waits. */
class AppCdsProcessRunner {
    private final Starter starter;

    AppCdsProcessRunner() {
        this(ProcessBuilder::start);
    }

    AppCdsProcessRunner(Starter starter) {
        this.starter = starter;
    }

    void run(List<String> command, Path directory, Path log, int timeoutSeconds) throws MojoExecutionException {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        // Do not inject Maven's agents, argfiles, VM-specific options or an ambient classpath.
        for (String key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
                "IBM_JAVA_OPTIONS", "OPENJ9_JAVA_OPTIONS", "CLASSPATH")) builder.environment().remove(key);
        Process child = null;
        MojoExecutionException failure = null;
        boolean interrupted = false;
        try {
            child = starter.start(builder);
            child.getOutputStream().close();
            if (!child.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new MojoExecutionException("AppCDS child timed out after " + timeoutSeconds + " seconds. Log: " + log);
            }
            if (child.exitValue() != 0) {
                throw new MojoExecutionException("AppCDS child failed with exit code " + child.exitValue() + ". Log: " + log);
            }
        } catch (IOException error) {
            failure = new MojoExecutionException("Cannot run AppCDS child. Log: " + log, error);
        } catch (InterruptedException error) {
            interrupted = true;
            failure = new MojoExecutionException("AppCDS child interrupted. Log: " + log, error);
        } catch (MojoExecutionException error) {
            failure = error;
        } finally {
            if (child != null && child.isAlive()) {
                // Reap the writer before the mojo can remove outputs or release its lock.
                // Clear interruption only for the two bounded cleanup waits, then restore it.
                interrupted |= Thread.interrupted();
                child.destroy();
                boolean stopped = false;
                try {
                    stopped = child.waitFor(2, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    interrupted = true;
                }
                if (!stopped) {
                    child.destroyForcibly();
                    try {
                        stopped = child.waitFor(2, TimeUnit.SECONDS);
                    } catch (InterruptedException error) {
                        interrupted = true;
                    }
                }
                if (!stopped && child.isAlive()) {
                    failure = new TerminationUnconfirmedException(
                            "AppCDS child forced termination unconfirmed; retain generation lock and inspect owned child. "
                                    + (failure == null ? "" : failure.getMessage()) + " Log: " + log, failure);
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (failure != null) throw failure;
    }

    static final class TerminationUnconfirmedException extends MojoExecutionException {
        TerminationUnconfirmedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @FunctionalInterface
    interface Starter {
        Process start(ProcessBuilder builder) throws IOException;
    }
}
