package com.reactor.rust.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Build-time scheduled task declaration. Requires the optional scheduler starter. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface Scheduled {
    enum Mode { FIXED_DELAY, FIXED_RATE }

    String name() default "";

    Mode mode() default Mode.FIXED_DELAY;

    long intervalMs() default -1L;

    String intervalProperty() default "";

    long initialDelayMs() default 0L;

    String initialDelayProperty() default "";

    String lockName() default "";

    long lockAtMostMs() default 60_000L;

    String lockAtMostProperty() default "";
}
