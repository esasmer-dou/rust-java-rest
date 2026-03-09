package com.reactor.rust.component;


import java.lang.reflect.Method;
import java.nio.ByteBuffer;

public final class CleanerUtils {

    private static final Method cleanerMethod;
    private static final Method cleanMethod;

    static {
        Method cMethod = null;
        Method clMethod = null;

        try {
            // Java 8 ve bazı JVM’lerde: DirectByteBuffer.cleaner()
            cMethod = Class.forName("java.nio.DirectByteBuffer")
                    .getMethod("cleaner");
            cMethod.setAccessible(true);

            // cleaner.clean()
            Class<?> cleanerClass = Class.forName("sun.misc.Cleaner");
            clMethod = cleanerClass.getMethod("clean");
            clMethod.setAccessible(true);

        } catch (Throwable t1) {
            try {
                // Java 9–21: jdk.internal.ref.Cleaner
                cMethod = Class.forName("java.nio.DirectByteBuffer")
                        .getDeclaredMethod("cleaner");
                cMethod.setAccessible(true);

                Class<?> cleanerClass = Class.forName("jdk.internal.ref.Cleaner");
                clMethod = cleanerClass.getDeclaredMethod("clean");
                clMethod.setAccessible(true);

            } catch (Throwable t2) {
                // No direct cleaner available - fallback to GC
            }
        }

        cleanerMethod = cMethod;
        cleanMethod = clMethod;
    }

    private CleanerUtils() {}

    public static void free(ByteBuffer buffer) {
        if (buffer == null) return;
        if (!buffer.isDirect()) return;

        if (cleanerMethod == null || cleanMethod == null) {
            // Fallback: GC’ye bırak
            return;
        }

        try {
            Object cleaner = cleanerMethod.invoke(buffer);
            if (cleaner != null) {
                cleanMethod.invoke(cleaner);
            }
        } catch (Throwable ignored) {
            // Eğer failure olursa GC’ye bırakarak degrade olur (safe fallback)
        }
    }
}
