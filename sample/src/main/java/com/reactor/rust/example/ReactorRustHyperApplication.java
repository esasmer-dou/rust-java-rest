package com.reactor.rust.example;

import com.reactor.rust.app.RestApplication;

public final class ReactorRustHyperApplication {

    private ReactorRustHyperApplication() {}

    public static void main(String[] args) {
        RestApplication.runStandard(ReactorRustHyperModule.INSTANCE);
    }
}
