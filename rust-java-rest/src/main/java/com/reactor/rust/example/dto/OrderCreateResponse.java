package com.reactor.rust.example.dto;

import com.dslplatform.json.CompiledJson;
import com.reactor.rust.annotations.Response;

@CompiledJson
@Response
public record OrderCreateResponse(
    int status,
    String message,
    int orderId
) {}
