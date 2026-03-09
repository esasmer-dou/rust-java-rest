package com.reactor.rust.http;

/**
 * Content type / media type constants.
 */
public final class MediaType {

    public static final String APPLICATION_JSON = "application/json";
    public static final String APPLICATION_JSON_UTF8 = "application/json;charset=UTF-8";
    public static final String APPLICATION_OCTET_STREAM = "application/octet-stream";
    public static final String APPLICATION_XML = "application/xml";
    public static final String TEXT_PLAIN = "text/plain";
    public static final String TEXT_HTML = "text/html";
    public static final String TEXT_XML = "text/xml";
    public static final String MULTIPART_FORM_DATA = "multipart/form-data";
    public static final String FORM_URLENCODED = "application/x-www-form-urlencoded";

    private MediaType() {}
}
