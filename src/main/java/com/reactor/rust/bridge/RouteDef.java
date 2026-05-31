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
    public final boolean asyncRoute;
    public final long maxRequestBodyBytes;
    public final long maxResponseBodyBytes;
    public final String directQueryIntName;
    public final int directQueryIntDefault;
    public final int directQueryIntMin;
    public final int directQueryIntMax;
    public final String directQueryLongName;
    public final long directQueryLongDefault;
    public final long directQueryLongMin;
    public final long directQueryLongMax;
    public final String directQueryBooleanName;
    public final boolean directQueryBooleanDefault;
    public final String directQueryDoubleName;
    public final double directQueryDoubleDefault;
    public final double directQueryDoubleMin;
    public final double directQueryDoubleMax;
    public final String directQueryShortName;
    public final short directQueryShortDefault;
    public final short directQueryShortMin;
    public final short directQueryShortMax;
    public final String directPathIntName;
    public final int directPathIntMin;
    public final int directPathIntMax;
    public final String directPathLongName;
    public final long directPathLongMin;
    public final long directPathLongMax;
    public final String directPathBooleanName;
    public final String directPathDoubleName;
    public final double directPathDoubleMin;
    public final double directPathDoubleMax;
    public final String directPathShortName;
    public final short directPathShortMin;
    public final short directPathShortMax;
    public final boolean directBodylessOutput;
    public final int nativeStaticResponseId;
    public final int nativeStaticFileResponseId;

    public RouteDef(String httpMethod,
                    String path,
                    int handlerId,
                    String requestType,
                    String responseType) {
        this(httpMethod, path, handlerId, requestType, responseType,
                isVoidRequestType(requestType), false, false, false, false, 0L, 0L);
    }

    public RouteDef(String httpMethod,
                    String path,
                    int handlerId,
                    String requestType,
                    String responseType,
                    long maxRequestBodyBytes,
                    long maxResponseBodyBytes) {
        this(httpMethod, path, handlerId, requestType, responseType,
                isVoidRequestType(requestType), false, false, false, false,
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
                bodyless, needsPathParams, needsQueryParams, needsHeaders, false,
                maxRequestBodyBytes, maxResponseBodyBytes,
                "", 0, Integer.MIN_VALUE, Integer.MAX_VALUE,
                "", 0L, Long.MIN_VALUE, Long.MAX_VALUE,
                "", false,
                "", 0.0d, -Double.MAX_VALUE, Double.MAX_VALUE,
                "", (short) 0, Short.MIN_VALUE, Short.MAX_VALUE,
                "", Integer.MIN_VALUE, Integer.MAX_VALUE,
                "", Long.MIN_VALUE, Long.MAX_VALUE,
                "",
                "", -Double.MAX_VALUE, Double.MAX_VALUE,
                "", Short.MIN_VALUE, Short.MAX_VALUE,
                false,
                0,
                0);
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
                    boolean asyncRoute,
                    long maxRequestBodyBytes,
                    long maxResponseBodyBytes) {
        this(httpMethod, path, handlerId, requestType, responseType,
                bodyless, needsPathParams, needsQueryParams, needsHeaders, asyncRoute,
                maxRequestBodyBytes, maxResponseBodyBytes,
                "", 0, Integer.MIN_VALUE, Integer.MAX_VALUE,
                "", 0L, Long.MIN_VALUE, Long.MAX_VALUE,
                "", false,
                "", 0.0d, -Double.MAX_VALUE, Double.MAX_VALUE,
                "", (short) 0, Short.MIN_VALUE, Short.MAX_VALUE,
                "", Integer.MIN_VALUE, Integer.MAX_VALUE,
                "", Long.MIN_VALUE, Long.MAX_VALUE,
                "",
                "", -Double.MAX_VALUE, Double.MAX_VALUE,
                "", Short.MIN_VALUE, Short.MAX_VALUE,
                false,
                0,
                0);
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
                    boolean asyncRoute,
                    long maxRequestBodyBytes,
                    long maxResponseBodyBytes,
                    String directQueryIntName,
                    int directQueryIntDefault,
                    int directQueryIntMin,
                    int directQueryIntMax) {
        this(httpMethod, path, handlerId, requestType, responseType,
                bodyless, needsPathParams, needsQueryParams, needsHeaders, asyncRoute,
                maxRequestBodyBytes, maxResponseBodyBytes,
                directQueryIntName, directQueryIntDefault, directQueryIntMin, directQueryIntMax,
                "", 0L, Long.MIN_VALUE, Long.MAX_VALUE,
                "", false,
                "", 0.0d, -Double.MAX_VALUE, Double.MAX_VALUE,
                "", (short) 0, Short.MIN_VALUE, Short.MAX_VALUE,
                "", Integer.MIN_VALUE, Integer.MAX_VALUE,
                "", Long.MIN_VALUE, Long.MAX_VALUE,
                "",
                "", -Double.MAX_VALUE, Double.MAX_VALUE,
                "", Short.MIN_VALUE, Short.MAX_VALUE,
                false,
                0,
                0);
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
                    boolean asyncRoute,
                    long maxRequestBodyBytes,
                    long maxResponseBodyBytes,
                    String directQueryIntName,
                    int directQueryIntDefault,
                    int directQueryIntMin,
                    int directQueryIntMax,
                    String directQueryLongName,
                    long directQueryLongDefault,
                    long directQueryLongMin,
                    long directQueryLongMax,
                    String directQueryBooleanName,
                    boolean directQueryBooleanDefault) {
        this(httpMethod, path, handlerId, requestType, responseType,
                bodyless, needsPathParams, needsQueryParams, needsHeaders, asyncRoute,
                maxRequestBodyBytes, maxResponseBodyBytes,
                directQueryIntName, directQueryIntDefault, directQueryIntMin, directQueryIntMax,
                directQueryLongName, directQueryLongDefault, directQueryLongMin, directQueryLongMax,
                directQueryBooleanName, directQueryBooleanDefault,
                "", 0.0d, -Double.MAX_VALUE, Double.MAX_VALUE,
                "", (short) 0, Short.MIN_VALUE, Short.MAX_VALUE,
                "", Integer.MIN_VALUE, Integer.MAX_VALUE,
                "", Long.MIN_VALUE, Long.MAX_VALUE,
                "",
                "", -Double.MAX_VALUE, Double.MAX_VALUE,
                "", Short.MIN_VALUE, Short.MAX_VALUE,
                false,
                0,
                0);
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
                    boolean asyncRoute,
                    long maxRequestBodyBytes,
                    long maxResponseBodyBytes,
                    String directQueryIntName,
                    int directQueryIntDefault,
                    int directQueryIntMin,
                    int directQueryIntMax,
                    String directQueryLongName,
                    long directQueryLongDefault,
                    long directQueryLongMin,
                    long directQueryLongMax,
                    String directQueryBooleanName,
                    boolean directQueryBooleanDefault,
                    String directQueryDoubleName,
                    double directQueryDoubleDefault,
                    double directQueryDoubleMin,
                    double directQueryDoubleMax,
                    String directQueryShortName,
                    short directQueryShortDefault,
                    short directQueryShortMin,
                    short directQueryShortMax,
                    String directPathIntName,
                    int directPathIntMin,
                    int directPathIntMax,
                    String directPathLongName,
                    long directPathLongMin,
                    long directPathLongMax,
                    String directPathBooleanName,
                    String directPathDoubleName,
                    double directPathDoubleMin,
                    double directPathDoubleMax,
                    String directPathShortName,
                    short directPathShortMin,
                    short directPathShortMax,
                    boolean directBodylessOutput,
                    int nativeStaticResponseId,
                    int nativeStaticFileResponseId) {
        this.httpMethod = httpMethod;
        this.path = path;
        this.handlerId = handlerId;
        this.requestType = requestType;
        this.responseType = responseType;
        this.bodyless = bodyless;
        this.needsPathParams = needsPathParams;
        this.needsQueryParams = needsQueryParams;
        this.needsHeaders = needsHeaders;
        this.asyncRoute = asyncRoute;
        this.maxRequestBodyBytes = maxRequestBodyBytes;
        this.maxResponseBodyBytes = maxResponseBodyBytes;
        this.directQueryIntName = directQueryIntName == null ? "" : directQueryIntName;
        this.directQueryIntDefault = directQueryIntDefault;
        this.directQueryIntMin = directQueryIntMin;
        this.directQueryIntMax = directQueryIntMax;
        this.directQueryLongName = directQueryLongName == null ? "" : directQueryLongName;
        this.directQueryLongDefault = directQueryLongDefault;
        this.directQueryLongMin = directQueryLongMin;
        this.directQueryLongMax = directQueryLongMax;
        this.directQueryBooleanName = directQueryBooleanName == null ? "" : directQueryBooleanName;
        this.directQueryBooleanDefault = directQueryBooleanDefault;
        this.directQueryDoubleName = directQueryDoubleName == null ? "" : directQueryDoubleName;
        this.directQueryDoubleDefault = directQueryDoubleDefault;
        this.directQueryDoubleMin = directQueryDoubleMin;
        this.directQueryDoubleMax = directQueryDoubleMax;
        this.directQueryShortName = directQueryShortName == null ? "" : directQueryShortName;
        this.directQueryShortDefault = directQueryShortDefault;
        this.directQueryShortMin = directQueryShortMin;
        this.directQueryShortMax = directQueryShortMax;
        this.directPathIntName = directPathIntName == null ? "" : directPathIntName;
        this.directPathIntMin = directPathIntMin;
        this.directPathIntMax = directPathIntMax;
        this.directPathLongName = directPathLongName == null ? "" : directPathLongName;
        this.directPathLongMin = directPathLongMin;
        this.directPathLongMax = directPathLongMax;
        this.directPathBooleanName = directPathBooleanName == null ? "" : directPathBooleanName;
        this.directPathDoubleName = directPathDoubleName == null ? "" : directPathDoubleName;
        this.directPathDoubleMin = directPathDoubleMin;
        this.directPathDoubleMax = directPathDoubleMax;
        this.directPathShortName = directPathShortName == null ? "" : directPathShortName;
        this.directPathShortMin = directPathShortMin;
        this.directPathShortMax = directPathShortMax;
        this.directBodylessOutput = directBodylessOutput;
        this.nativeStaticResponseId = nativeStaticResponseId;
        this.nativeStaticFileResponseId = nativeStaticFileResponseId;
    }

    private static boolean isVoidRequestType(String requestType) {
        return "java.lang.Void".equals(requestType) || "void".equals(requestType);
    }
}
