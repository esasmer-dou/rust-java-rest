package com.reactor.rust.bridge;

import java.lang.reflect.Method;

/** Starter SPI for attaching security or policy guards to selected routes. */
public interface RequestGuardFactory {
    default int order() {
        return 100;
    }

    RequestGuard create(Class<?> owner, Method method);
}
