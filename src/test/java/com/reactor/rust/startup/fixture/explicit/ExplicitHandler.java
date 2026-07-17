package com.reactor.rust.startup.fixture.explicit;

import com.reactor.rust.annotations.GetMapping;

public final class ExplicitHandler {

    @GetMapping("/explicit")
    public String read() {
        return "ok";
    }
}
