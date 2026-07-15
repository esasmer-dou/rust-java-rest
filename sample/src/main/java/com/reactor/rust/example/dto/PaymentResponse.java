package com.reactor.rust.example.dto;

import com.dslplatform.json.CompiledJson;
import com.reactor.rust.annotations.Response;

/**
 * Payment Response DTO.
 */
@CompiledJson
@Response
public record PaymentResponse(
    String transactionId,
    String method,
    String status
) {}
