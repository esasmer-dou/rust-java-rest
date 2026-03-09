package com.reactor.rust.dto;

import com.dslplatform.json.CompiledJson;
import com.reactor.rust.annotations.Request;

@CompiledJson
@Request
public record OrderCreateRequest(
    int orderId,
    double amount
) {}
