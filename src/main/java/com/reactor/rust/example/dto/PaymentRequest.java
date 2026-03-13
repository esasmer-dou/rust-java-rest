package com.reactor.rust.example.dto;

import com.dslplatform.json.CompiledJson;
import com.reactor.rust.annotations.Request;

/**
 * Payment Request DTO.
 */
@CompiledJson
@Request
public record PaymentRequest(
    String orderId,
    double amount
) {}
