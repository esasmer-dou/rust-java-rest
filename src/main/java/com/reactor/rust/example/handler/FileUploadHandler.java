package com.reactor.rust.example.handler;

import com.reactor.rust.annotations.*;
import com.reactor.rust.di.annotation.Component;
import com.reactor.rust.http.ResponseEntity;
import com.reactor.rust.multipart.MultipartFile;
import com.reactor.rust.multipart.MultipartParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * File Upload Handler - Demonstrates multipart file upload.
 *
 * Features:
 * - Multipart form-data parsing
 * - File upload handling
 * - Text field extraction
 */
@Component
@RequestMapping("/upload")
public class FileUploadHandler {

    /**
     * POST /upload/single
     * Upload a single file.
     * Content-Type: multipart/form-data
     */
    @PostMapping("/single")
    public ResponseEntity<UploadResponse> uploadSingle(
            @HeaderParam("Content-Type") String contentType,
            @RequestBody byte[] body
    ) {
        Map<String, Object> parts = MultipartParser.parse(body, contentType);

        if (parts.isEmpty()) {
            return ResponseEntity.badRequest(
                new UploadResponse("error", "No parts found in multipart data", List.of())
            );
        }

        List<FileInfo> files = new ArrayList<>();

        for (Map.Entry<String, Object> entry : parts.entrySet()) {
            if (entry.getValue() instanceof MultipartFile file) {
                files.add(new FileInfo(
                    file.getName(),
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getSize(),
                    file.getExtension()
                ));
            }
        }

        if (files.isEmpty()) {
            return ResponseEntity.badRequest(
                new UploadResponse("error", "No files found in upload", List.of())
            );
        }

        return ResponseEntity.ok(new UploadResponse(
            "success",
            "File uploaded successfully",
            files
        ));
    }

    /**
     * POST /upload/multiple
     * Upload multiple files.
     * Content-Type: multipart/form-data
     */
    @PostMapping("/multiple")
    public ResponseEntity<UploadResponse> uploadMultiple(
            @HeaderParam("Content-Type") String contentType,
            @RequestBody byte[] body
    ) {
        Map<String, Object> parts = MultipartParser.parse(body, contentType);

        List<FileInfo> files = new ArrayList<>();

        for (Map.Entry<String, Object> entry : parts.entrySet()) {
            if (entry.getValue() instanceof MultipartFile file) {
                files.add(new FileInfo(
                    file.getName(),
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getSize(),
                    file.getExtension()
                ));
            }
        }

        return ResponseEntity.ok(new UploadResponse(
            "success",
            files.size() + " file(s) uploaded successfully",
            files
        ));
    }

    /**
     * POST /upload/form
     * Upload form with file and text fields.
     * Content-Type: multipart/form-data
     */
    @PostMapping("/form")
    public ResponseEntity<FormUploadResponse> uploadForm(
            @HeaderParam("Content-Type") String contentType,
            @RequestBody byte[] body
    ) {
        Map<String, Object> parts = MultipartParser.parse(body, contentType);

        String username = null;
        String email = null;
        FileInfo uploadedFile = null;

        for (Map.Entry<String, Object> entry : parts.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof MultipartFile file) {
                uploadedFile = new FileInfo(
                    file.getName(),
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getSize(),
                    file.getExtension()
                );
            } else if (value instanceof String textValue) {
                switch (key) {
                    case "username" -> username = textValue;
                    case "email" -> email = textValue;
                }
            }
        }

        return ResponseEntity.ok(new FormUploadResponse(
            username != null ? username : "not provided",
            email != null ? email : "not provided",
            uploadedFile
        ));
    }

    /**
     * POST /upload/avatar
     * Upload avatar image with validation.
     * Accepts only: png, jpg, jpeg, gif, webp
     */
    @PostMapping("/avatar")
    public ResponseEntity<AvatarResponse> uploadAvatar(
            @HeaderParam("Content-Type") String contentType,
            @RequestBody byte[] body
    ) {
        Map<String, Object> parts = MultipartParser.parse(body, contentType);

        MultipartFile avatar = null;
        for (Object value : parts.values()) {
            if (value instanceof MultipartFile file && "avatar".equals(file.getName())) {
                avatar = file;
                break;
            }
        }

        if (avatar == null) {
            return ResponseEntity.badRequest(
                new AvatarResponse("error", "No avatar file provided", null)
            );
        }

        // Validate file type
        if (!avatar.hasExtension("png", "jpg", "jpeg", "gif", "webp")) {
            return ResponseEntity.badRequest(
                new AvatarResponse("error", "Invalid file type. Allowed: png, jpg, jpeg, gif, webp", null)
            );
        }

        // Validate size (max 5MB)
        if (avatar.getSize() > 5 * 1024 * 1024) {
            return ResponseEntity.badRequest(
                new AvatarResponse("error", "File too large. Maximum size: 5MB", null)
            );
        }

        return ResponseEntity.ok(new AvatarResponse(
            "success",
            "Avatar uploaded successfully",
            new FileInfo(
                avatar.getName(),
                avatar.getOriginalFilename(),
                avatar.getContentType(),
                avatar.getSize(),
                avatar.getExtension()
            )
        ));
    }

    // DTOs
    public record UploadResponse(String status, String message, List<FileInfo> files) {}
    public record FileInfo(String fieldName, String filename, String contentType, long size, String extension) {}
    public record FormUploadResponse(String username, String email, FileInfo file) {}
    public record AvatarResponse(String status, String message, FileInfo file) {}
}
