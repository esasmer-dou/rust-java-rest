package com.reactor.rust.bridge;

public class RouteDef {

    public final String httpMethod;
    public final String path;
    public final int handlerId;
    public final String requestType;
    public final String responseType;

    public RouteDef(String httpMethod,
                    String path,
                    int handlerId,
                    String requestType,
                    String responseType) {
        this.httpMethod = httpMethod;
        this.path = path;
        this.handlerId = handlerId;
        this.requestType = requestType;
        this.responseType = responseType;
    }
}
