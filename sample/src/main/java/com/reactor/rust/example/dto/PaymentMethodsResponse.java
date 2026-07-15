package com.reactor.rust.example.dto;

import com.dslplatform.json.CompiledJson;
import com.reactor.rust.annotations.Response;
import java.util.List;

/**
 * Payment Methods Response DTO.
 */
@CompiledJson
@Response
public record PaymentMethodsResponse(
    List<PaymentMethodInfo> methods
) {
    /**
     * Payment method information.
     */
    @CompiledJson
    public record PaymentMethodInfo(
        String id,
        String name,
        boolean isPrimary
    ) {}
}
