package com.reactor.rust.multipart;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Represents an uploaded file from a multipart form request.
 */
public final class MultipartFile {

    private final String name;
    private final String originalFilename;
    private final String contentType;
    private final byte[] bytes;
    private final long size;

    public MultipartFile(String name, String originalFilename, String contentType, byte[] bytes) {
        this.name = name;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.bytes = bytes;
        this.size = bytes != null ? bytes.length : 0;
    }

    /**
     * Get the form field name.
     */
    public String getName() {
        return name;
    }

    /**
     * Get the original filename from the client.
     */
    public String getOriginalFilename() {
        return originalFilename;
    }

    /**
     * Get the content type (MIME type).
     */
    public String getContentType() {
        return contentType;
    }

    /**
     * Check if file is empty.
     */
    public boolean isEmpty() {
        return bytes == null || bytes.length == 0;
    }

    /**
     * Get file size in bytes.
     */
    public long getSize() {
        return size;
    }

    /**
     * Get file contents as byte array.
     */
    public byte[] getBytes() {
        return bytes != null ? bytes : new byte[0];
    }

    /**
     * Get input stream for reading file contents.
     */
    public InputStream getInputStream() {
        return new ByteArrayInputStream(bytes != null ? bytes : new byte[0]);
    }

    /**
     * Get file contents as string (UTF-8).
     */
    public String getString() {
        return getString(StandardCharsets.UTF_8);
    }

    /**
     * Get file contents as string with specified charset.
     */
    public String getString(Charset charset) {
        return new String(bytes != null ? bytes : new byte[0], charset);
    }

    /**
     * Get file extension (lowercase, without dot).
     */
    public String getExtension() {
        if (originalFilename == null || originalFilename.isEmpty()) {
            return "";
        }
        int dotIndex = originalFilename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == originalFilename.length() - 1) {
            return "";
        }
        return originalFilename.substring(dotIndex + 1).toLowerCase();
    }

    /**
     * Check if file has allowed extension.
     */
    public boolean hasExtension(String... allowedExtensions) {
        String ext = getExtension();
        for (String allowed : allowedExtensions) {
            if (ext.equalsIgnoreCase(allowed.replace(".", ""))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "MultipartFile{" +
                "name='" + name + '\'' +
                ", originalFilename='" + originalFilename + '\'' +
                ", contentType='" + contentType + '\'' +
                ", size=" + size +
                '}';
    }
}
