package com.reactor.rust.smoke;

import com.reactor.rust.annotations.ReactorApplication;

@ReactorApplication(
        name = "Platform Integration Smoke",
        version = "4.4.1",
        description = "Compile-time verification for REST, Dubbo, and cache starters")
public final class SmokeApplication {
    private SmokeApplication() {}
}
