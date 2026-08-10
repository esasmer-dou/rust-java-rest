package com.reactor.rust.http.client;

import com.reactor.rust.annotations.HttpMethod;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Build-time outbound HTTP method declaration. Methods must return CompletionStage. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface HttpExchange {
    HttpMethod method() default HttpMethod.GET;

    String path();

    long timeoutMs() default 0L;

    int retries() default -1;

    boolean idempotent() default false;

    String contentType() default "application/json; charset=utf-8";

    String accept() default "application/json";
}
