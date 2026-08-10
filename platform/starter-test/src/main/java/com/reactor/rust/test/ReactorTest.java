package com.reactor.rust.test;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Starts one isolated Rust-Java application for a JUnit test class. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(ReactorTestExtension.class)
public @interface ReactorTest {
    Class<?> application();
    String[] properties() default {};
}
