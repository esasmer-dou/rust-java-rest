package com.reactor.examples.minimal;

import com.reactor.rust.app.RestApplication;

public final class MinimalApplication {

    private MinimalApplication() {}

    public static void main(String[] args) {
        RestApplication.run(context -> context.handlers(new HelloHandler()));
    }
}
