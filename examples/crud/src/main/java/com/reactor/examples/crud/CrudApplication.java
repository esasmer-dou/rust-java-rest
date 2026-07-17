package com.reactor.examples.crud;

import com.reactor.rust.app.RestApplication;

public final class CrudApplication {

    private CrudApplication() {}

    public static void main(String[] args) {
        RestApplication.run(context -> context.handlers(new ProductHandler()));
    }
}
