package com.reactor.rust.example.dto;

import com.dslplatform.json.CompiledJson;
import com.reactor.rust.annotations.Response;
import com.reactor.rust.annotations.GenerateDirectJsonWriter;

/**
 * Payment Response DTO.
 */
@CompiledJson
@Response
@GenerateDirectJsonWriter
public record PaymentResponse(
    String transactionId,
    String method,
    String status
) {}
