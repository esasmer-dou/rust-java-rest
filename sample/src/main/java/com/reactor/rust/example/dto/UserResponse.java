package com.reactor.rust.example.dto;

import com.dslplatform.json.CompiledJson;
import com.reactor.rust.annotations.Response;

/**
 * User Response DTO
 */
@CompiledJson
@Response
public record UserResponse(
        int id,
        String name,
        String email
) {}
