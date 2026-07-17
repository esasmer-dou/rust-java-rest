package com.reactor.rust.example.dto;

import com.dslplatform.json.CompiledJson;
import com.reactor.rust.annotations.Response;
import com.reactor.rust.annotations.GenerateDirectJsonWriter;

/**
 * User Response DTO
 */
@CompiledJson
@Response
@GenerateDirectJsonWriter
public record UserResponse(
        int id,
        String name,
        String email
) {}
