package com.reactor.examples.websocket;

import com.reactor.rust.app.RestApplication;

public final class WebSocketApplication {

    private WebSocketApplication() {}

    public static void main(String[] args) {
        RestApplication.runStandard(context -> context.scan("com.reactor.examples.websocket"));
    }
}
