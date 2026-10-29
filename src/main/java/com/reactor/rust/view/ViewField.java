package com.reactor.rust.view;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Fields are laid out in increasing index order. Explicit offsets must be aligned and non-overlapping. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface ViewField {
    int index();
    int offset() default -1;
}
