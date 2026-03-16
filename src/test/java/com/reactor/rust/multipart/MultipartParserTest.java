package com.reactor.rust.multipart;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MultipartParser.
 */
class MultipartParserTest {

    @Test
    @DisplayName("Parse simple text field")
    void testParseSimpleTextField() {
        String boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW";
        String contentType = "multipart/form-data; boundary=" + boundary;

        String body = """
            ------WebKitFormBoundary7MA4YWxkTrZu0gW\r
            Content-Disposition: form-data; name="username"\r
            \r
            john_doe\r
            ------WebKitFormBoundary7MA4YWxkTrZu0gW--\r
            """;

        Map<String, Object> result = MultipartParser.parse(body.getBytes(), contentType);

        assertEquals(1, result.size());
        assertTrue(result.containsKey("username"));
        assertEquals("john_doe", result.get("username"));
    }

    @Test
    @DisplayName("Parse multiple text fields")
    void testParseMultipleTextFields() {
        String boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW";
        String contentType = "multipart/form-data; boundary=" + boundary;

        String body = """
            ------WebKitFormBoundary7MA4YWxkTrZu0gW\r
            Content-Disposition: form-data; name="username"\r
            \r
            john_doe\r
            ------WebKitFormBoundary7MA4YWxkTrZu0gW\r
            Content-Disposition: form-data; name="email"\r
            \r
            john@example.com\r
            ------WebKitFormBoundary7MA4YWxkTrZu0gW--\r
            """;

        Map<String, Object> result = MultipartParser.parse(body.getBytes(), contentType);

        assertEquals(2, result.size());
        assertEquals("john_doe", result.get("username"));
        assertEquals("john@example.com", result.get("email"));
    }

    @Test
    @DisplayName("Parse file upload")
    void testParseFileUpload() {
        String boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW";
        String contentType = "multipart/form-data; boundary=" + boundary;

        String body = """
            ------WebKitFormBoundary7MA4YWxkTrZu0gW\r
            Content-Disposition: form-data; name="file"; filename="test.txt"\r
            Content-Type: text/plain\r
            \r
            Hello, World!\r
            ------WebKitFormBoundary7MA4YWxkTrZu0gW--\r
            """;

        Map<String, Object> result = MultipartParser.parse(body.getBytes(), contentType);

        assertEquals(1, result.size());
        assertTrue(result.get("file") instanceof MultipartFile);

        MultipartFile file = (MultipartFile) result.get("file");
        assertEquals("file", file.getName());
        assertEquals("test.txt", file.getOriginalFilename());
        assertEquals("text/plain", file.getContentType());
        assertEquals("Hello, World!", file.getString());
        assertEquals(13, file.getSize());
        assertFalse(file.isEmpty());
    }

    @Test
    @DisplayName("Parse mixed fields and files")
    void testParseMixedFieldsAndFiles() {
        String boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW";
        String contentType = "multipart/form-data; boundary=" + boundary;

        String body = """
            ------WebKitFormBoundary7MA4YWxkTrZu0gW\r
            Content-Disposition: form-data; name="username"\r
            \r
            john_doe\r
            ------WebKitFormBoundary7MA4YWxkTrZu0gW\r
            Content-Disposition: form-data; name="avatar"; filename="avatar.png"\r
            Content-Type: image/png\r
            \r
            \u0089PNG\r
            ------WebKitFormBoundary7MA4YWxkTrZu0gW\r
            Content-Disposition: form-data; name="bio"\r
            \r
            Software Developer\r
            ------WebKitFormBoundary7MA4YWxkTrZu0gW--\r
            """;

        Map<String, Object> result = MultipartParser.parse(body.getBytes(), contentType);

        assertEquals(3, result.size());
        assertEquals("john_doe", result.get("username"));
        assertEquals("Software Developer", result.get("bio"));

        assertTrue(result.get("avatar") instanceof MultipartFile);
        MultipartFile avatar = (MultipartFile) result.get("avatar");
        assertEquals("avatar.png", avatar.getOriginalFilename());
        assertEquals("image/png", avatar.getContentType());
    }

    @Test
    @DisplayName("Parse with quoted boundary")
    void testParseWithQuotedBoundary() {
        String boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW";
        String contentType = "multipart/form-data; boundary=\"" + boundary + "\"";

        String body = """
            ------WebKitFormBoundary7MA4YWxkTrZu0gW\r
            Content-Disposition: form-data; name="field"\r
            \r
            value\r
            ------WebKitFormBoundary7MA4YWxkTrZu0gW--\r
            """;

        Map<String, Object> result = MultipartParser.parse(body.getBytes(), contentType);

        assertEquals(1, result.size());
        assertEquals("value", result.get("field"));
    }

    @Test
    @DisplayName("Handle null data")
    void testHandleNullData() {
        Map<String, Object> result = MultipartParser.parse(null, "multipart/form-data; boundary=test");
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Handle empty data")
    void testHandleEmptyData() {
        Map<String, Object> result = MultipartParser.parse(new byte[0], "multipart/form-data; boundary=test");
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Handle null content type")
    void testHandleNullContentType() {
        Map<String, Object> result = MultipartParser.parse("data".getBytes(), null);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Handle missing boundary")
    void testHandleMissingBoundary() {
        Map<String, Object> result = MultipartParser.parse("data".getBytes(), "multipart/form-data");
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Enforce max file size")
    void testEnforceMaxFileSize() {
        String boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW";
        String contentType = "multipart/form-data; boundary=" + boundary;

        // Create a body with a 20-byte file content
        byte[] fileContent = new byte[20];
        for (int i = 0; i < fileContent.length; i++) {
            fileContent[i] = (byte) ('A' + i);
        }

        String header = """
            ------WebKitFormBoundary7MA4YWxkTrZu0gW\r
            Content-Disposition: form-data; name="file"; filename="large.txt"\r
            Content-Type: application/octet-stream\r
            \r
            """;
        String footer = "\r\n------WebKitFormBoundary7MA4YWxkTrZu0gW--\r\n";

        byte[] body = new byte[header.getBytes().length + fileContent.length + footer.getBytes().length];
        System.arraycopy(header.getBytes(), 0, body, 0, header.getBytes().length);
        System.arraycopy(fileContent, 0, body, header.getBytes().length, fileContent.length);
        System.arraycopy(footer.getBytes(), 0, body, header.getBytes().length + fileContent.length, footer.getBytes().length);

        // Max file size = 10 bytes, file is 20 bytes
        Map<String, Object> result = MultipartParser.parse(body, contentType, 10);

        // File should be excluded because it exceeds max size
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("MultipartFile getExtension")
    void testMultipartFileGetExtension() {
        MultipartFile file1 = new MultipartFile("file", "document.pdf", "application/pdf", new byte[10]);
        MultipartFile file2 = new MultipartFile("file", "image.PNG", "image/png", new byte[10]);
        MultipartFile file3 = new MultipartFile("file", "noextension", "application/octet-stream", new byte[10]);
        MultipartFile file4 = new MultipartFile("file", "endswithdot.", "application/octet-stream", new byte[10]);

        assertEquals("pdf", file1.getExtension());
        assertEquals("png", file2.getExtension());
        assertEquals("", file3.getExtension());
        assertEquals("", file4.getExtension());
    }

    @Test
    @DisplayName("MultipartFile hasExtension")
    void testMultipartFileHasExtension() {
        MultipartFile file = new MultipartFile("file", "document.pdf", "application/pdf", new byte[10]);

        assertTrue(file.hasExtension("pdf"));
        assertTrue(file.hasExtension(".pdf"));
        assertTrue(file.hasExtension("PDF"));
        assertFalse(file.hasExtension("txt"));
        assertFalse(file.hasExtension("doc"));
    }

    @Test
    @DisplayName("MultipartFile isEmpty")
    void testMultipartFileIsEmpty() {
        MultipartFile emptyFile = new MultipartFile("file", "empty.txt", "text/plain", new byte[0]);
        MultipartFile nullBytesFile = new MultipartFile("file", "null.txt", "text/plain", null);
        MultipartFile normalFile = new MultipartFile("file", "normal.txt", "text/plain", new byte[10]);

        assertTrue(emptyFile.isEmpty());
        assertTrue(nullBytesFile.isEmpty());
        assertFalse(normalFile.isEmpty());
    }

    @Test
    @DisplayName("MultipartFile getInputStream")
    void testMultipartFileGetInputStream() throws Exception {
        byte[] content = "Hello".getBytes();
        MultipartFile file = new MultipartFile("file", "test.txt", "text/plain", content);

        var is = file.getInputStream();
        assertNotNull(is);
        assertArrayEquals(content, is.readAllBytes());
    }

    @Test
    @DisplayName("MultipartFile getString with charset")
    void testMultipartFileGetStringWithCharset() {
        byte[] content = "Hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MultipartFile file = new MultipartFile("file", "test.txt", "text/plain", content);

        assertEquals("Hello", file.getString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("MultipartFile toString")
    void testMultipartFileToString() {
        MultipartFile file = new MultipartFile("avatar", "pic.png", "image/png", new byte[100]);

        String str = file.toString();
        assertTrue(str.contains("avatar"));
        assertTrue(str.contains("pic.png"));
        assertTrue(str.contains("image/png"));
        assertTrue(str.contains("100"));
    }
}
