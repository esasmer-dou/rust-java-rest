package com.reactor.rust.staticfiles;

import com.reactor.rust.annotations.StaticFiles;

import java.util.List;

/**
 * Scanner for @StaticFiles annotations.
 * Registers static file configurations.
 */
public final class StaticFileScanner {

    private StaticFileScanner() {}

    /**
     * Scan all registered beans for @StaticFiles annotations.
     */
    public static void scanAndRegister(List<Object> beans) {
        for (Object bean : beans) {
            StaticFiles staticFiles = bean.getClass().getAnnotation(StaticFiles.class);
            if (staticFiles != null) {
                StaticFileRegistry.getInstance().register(staticFiles);
            }
        }
    }
}
