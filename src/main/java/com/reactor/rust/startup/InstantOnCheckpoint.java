package com.reactor.rust.startup;

import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.logging.FrameworkLogger;
import com.reactor.rust.metrics.Metrics;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Optional OpenJ9 CRIU checkpoint hook for container images.
 *
 * <p>This class intentionally uses reflection so the framework still compiles and runs on
 * non-OpenJ9 JVMs. It must be called after framework metadata is ready but before HTTP sockets,
 * database pools, ZooKeeper clients, or RPC connections are opened.</p>
 */
public final class InstantOnCheckpoint {

    private static final String CRIU_SUPPORT_CLASS = "org.eclipse.openj9.criu.CRIUSupport";
    private static volatile boolean restored;

    private InstantOnCheckpoint() {
    }

    public static boolean checkpointIfEnabled() {
        if (!PropertiesLoader.getBoolean("reactor.instanton.checkpoint.enabled", false)) {
            return false;
        }
        try (StartupTimeline.Scope ignored = StartupTimeline.phase("instanton.checkpoint")) {
            return checkpoint();
        }
    }

    public static boolean isRestored() {
        return restored;
    }

    private static boolean checkpoint() {
        Metrics.getInstance().setGauge("reactor.startup.instanton.checkpoint.attempted", 1);
        boolean failOnUnavailable = PropertiesLoader.getBoolean(
                "reactor.instanton.checkpoint.fail-on-unavailable",
                true
        );
        try {
            Path imageDir = Path.of(PropertiesLoader.get(
                    "reactor.instanton.checkpoint.dir",
                    "/checkpoint"
            ));
            Files.createDirectories(imageDir);

            Class<?> criuSupportClass = Class.forName(CRIU_SUPPORT_CLASS);
            if (!invokeStaticBoolean(criuSupportClass, "isCRIUSupportEnabled")) {
                String errorMessage = invokeStaticString(criuSupportClass, "getErrorMessage");
                return unavailable(
                        "OpenJ9 CRIU support is not enabled. Start Java with "
                                + "--add-modules openj9.criu -XX:+EnableCRIUSupport. "
                                + nullToEmpty(errorMessage),
                        failOnUnavailable
                );
            }
            if (!invokeStaticBoolean(criuSupportClass, "isCheckpointAllowed")) {
                return unavailable("OpenJ9 CRIU checkpoint is not allowed in the current process state",
                        failOnUnavailable);
            }

            Object criu = criuSupportClass.getMethod("getCRIUSupport").invoke(null);
            invoke(criu, "setImageDir", new Class<?>[]{Path.class}, imageDir);
            invokeOptional(criu, "setWorkDir", new Class<?>[]{Path.class}, imageDir);
            invokeOptional(criu, "setLeaveRunning", new Class<?>[]{boolean.class},
                    PropertiesLoader.getBoolean("reactor.instanton.checkpoint.leave-running", false));
            invokeOptional(criu, "setShellJob", new Class<?>[]{boolean.class},
                    PropertiesLoader.getBoolean("reactor.instanton.checkpoint.shell-job", true));
            invokeOptional(criu, "setFileLocks", new Class<?>[]{boolean.class},
                    PropertiesLoader.getBoolean("reactor.instanton.checkpoint.file-locks", true));
            invokeOptional(criu, "setAutoDedup", new Class<?>[]{boolean.class},
                    PropertiesLoader.getBoolean("reactor.instanton.checkpoint.auto-dedup", false));
            invokeOptional(criu, "setTCPClose", new Class<?>[]{boolean.class},
                    PropertiesLoader.getBoolean("reactor.instanton.checkpoint.tcp-close", true));

            String logFile = PropertiesLoader.get("reactor.instanton.checkpoint.log-file", "checkpoint.log");
            if (!logFile.isBlank()) {
                invokeOptional(criu, "setLogFile", new Class<?>[]{String.class}, Path.of(logFile).getFileName().toString());
            }
            invokeOptional(criu, "setLogLevel", new Class<?>[]{int.class},
                    PropertiesLoader.getInt("reactor.instanton.checkpoint.log-level", 4));
            invokeOptional(criu, "registerPostRestoreHook", new Class<?>[]{Runnable.class}, (Runnable) () -> {
                restored = true;
                StartupTimeline.mark("instanton.post_restore_hook");
                Metrics.getInstance().setGauge("reactor.startup.instanton.restored", 1);
            });

            FrameworkLogger.warn("[InstantOn] Taking OpenJ9 CRIU checkpoint at " + imageDir.toAbsolutePath());
            invoke(criu, "checkpointJVM", new Class<?>[0]);

            // Reached after restore, or when leave-running=true is explicitly configured.
            restored = true;
            StartupTimeline.restoreResumed();
            Metrics.getInstance().setGauge("reactor.startup.instanton.restored", 1);
            return true;
        } catch (ClassNotFoundException e) {
            return unavailable("OpenJ9 CRIU module is not available. Use IBM Semeru/OpenJ9 and add "
                    + "--add-modules openj9.criu.", failOnUnavailable);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return unavailable("OpenJ9 CRIU checkpoint failed: " + cause, failOnUnavailable);
        } catch (Exception e) {
            return unavailable("OpenJ9 CRIU checkpoint failed: " + e, failOnUnavailable);
        }
    }

    private static boolean unavailable(String message, boolean failOnUnavailable) {
        Metrics.getInstance().setGauge("reactor.startup.instanton.checkpoint.unavailable", 1);
        if (failOnUnavailable) {
            throw new IllegalStateException(message);
        }
        FrameworkLogger.warn("[InstantOn] " + message);
        return false;
    }

    private static boolean invokeStaticBoolean(Class<?> clazz, String methodName) throws Exception {
        return (Boolean) clazz.getMethod(methodName).invoke(null);
    }

    private static String invokeStaticString(Class<?> clazz, String methodName) throws Exception {
        Object value = clazz.getMethod(methodName).invoke(null);
        return value == null ? "" : value.toString();
    }

    private static Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args)
            throws Exception {
        Method method = target.getClass().getMethod(methodName, parameterTypes);
        return method.invoke(target, args);
    }

    private static void invokeOptional(Object target, String methodName, Class<?>[] parameterTypes, Object... args)
            throws Exception {
        try {
            invoke(target, methodName, parameterTypes, args);
        } catch (NoSuchMethodException ignored) {
            FrameworkLogger.debug("[InstantOn] Optional CRIU method not available: " + methodName);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
