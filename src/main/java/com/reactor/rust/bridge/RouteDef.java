package com.reactor.rust.bridge;

public class RouteDef {

    public final String httpMethod;
    public final String path;
    public final int handlerId;
    public final String requestType;
    public final String responseType;
    public final boolean bodyless;
    public final boolean needsPathParams;
    public final boolean needsQueryParams;
    public final boolean needsHeaders;
    public final long maxRequestBodyBytes;
    public final long maxResponseBodyBytes;
    public final String directQueryIntName;
    public final int directQueryIntDefault;
    public final int directQueryIntMin;
    public final int directQueryIntMax;

    public RouteDef(String httpMethod,
                    String path,
                    int handlerId,
                    String requestType,
                    String responseType) {
        this(httpMethod, path, handlerId, requestType, responseType,
                isVoidRequestType(requestType), false, false, false, 0L, 0L);
    }

    public RouteDef(String httpMethod,
                    String path,
                    int handlerId,
                    String requestType,
                    String responseType,
                    long maxRequestBodyBytes,
                    long maxResponseBodyBytes) {
        this(httpMethod, path, handlerId, requestType, responseType,
                isVoidRequestType(requestType), false, false, false,
                maxRequestBodyBytes, maxResponseBodyBytes);
    }

    public RouteDef(String httpMethod,
                    String path,
                    int handlerId,
                    String requestType,
                    String responseType,
                    boolean bodyless,
                    boolean needsPathParams,
                    boolean needsQueryParams,
                    boolean needsHeaders,
                    long maxRequestBodyBytes,
                    long maxResponseBodyBytes) {
        this(httpMethod, path, handlerId, requestType, responseType,
                bodyless, needsPathParams, needsQueryParams, needsHeaders,
                maxRequestBodyBytes, maxResponseBodyBytes,
                "", 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    public RouteDef(String httpMethod,
                    String path,
                    int handlerId,
                    String requestType,
                    String responseType,
                    boolean bodyless,
                    boolean needsPathParams,
                    boolean needsQueryParams,
                    boolean needsHeaders,
                    long maxRequestBodyBytes,
                    long maxResponseBodyBytes,
                    String directQueryIntName,
                    int directQueryIntDefault,
                    int directQueryIntMin,
                    int directQueryIntMax) {
        this.httpMethod = httpMethod;
        this.path = path;
        this.handlerId = handlerId;
        this.requestType = requestType;
        this.responseType = responseType;
        this.bodyless = bodyless;
        this.needsPathParams = needsPathParams;
        this.needsQueryParams = needsQueryParams;
        this.needsHeaders = needsHeaders;
        this.maxRequestBodyBytes = maxRequestBodyBytes;
        this.maxResponseBodyBytes = maxResponseBodyBytes;
        this.directQueryIntName = directQueryIntName == null ? "" : directQueryIntName;
        this.directQueryIntDefault = directQueryIntDefault;
        this.directQueryIntMin = directQueryIntMin;
        this.directQueryIntMax = directQueryIntMax;
    }

    private static boolean isVoidRequestType(String requestType) {
        return "java.lang.Void".equals(requestType) || "void".equals(requestType);
    }
}
