package com.reactor.rust.example.dto;

import com.dslplatform.json.CompiledJson;
import com.reactor.rust.annotations.Field;
import com.reactor.rust.annotations.Request;

/**
 * Payment Request DTO - Example for @Request + @Field (Constraint #9)
 *
 * @Request marks this as a REST API request body
 * @Field adds validation rules
 */
@CompiledJson
@Request
public record PaymentRequest(
        @Field(required = true)
        String orderId,

        @Field(required = true, min = 0.01, max = 1000000.0)
        double amount,

        @Field(required = false, pattern = "^[A-Z]{3}$")
        String currency,

        @Field(defaultValue = "PENDING")
        String status
) {}
