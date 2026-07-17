package com.reactor.examples.upload;

import com.reactor.rust.app.RestApplication;

public final class UploadApplication {

    private UploadApplication() {}

    public static void main(String[] args) {
        RestApplication.run(context -> context.handlers(new UploadHandler()));
    }
}
