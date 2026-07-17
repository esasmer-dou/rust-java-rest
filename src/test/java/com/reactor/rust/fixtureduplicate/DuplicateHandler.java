package com.reactor.rust.fixtureduplicate;

import com.reactor.rust.annotations.GetMapping;

public final class DuplicateHandler {

    @GetMapping("/same")
    public String read() {
        return "first";
    }

    @GetMapping("/same")
    public String read(String ignored) {
        return "second";
    }
}
