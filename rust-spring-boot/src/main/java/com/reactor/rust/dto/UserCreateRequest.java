package com.reactor.rust.dto;

import com.dslplatform.json.CompiledJson;
import com.reactor.rust.annotations.NotBlank;
import com.reactor.rust.annotations.Email;
import com.reactor.rust.annotations.Request;

/**
 * User Create Request DTO
 * Demonstrates validation annotations.
 */
@CompiledJson
@Request
public record UserCreateRequest(
        @NotBlank(message = "Name is required")
        String name,

        @Email(message = "Invalid email format")
        String email
) {}
