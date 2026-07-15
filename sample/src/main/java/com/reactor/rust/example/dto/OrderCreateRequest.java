package com.reactor.rust.example.dto;

import com.dslplatform.json.CompiledJson;
import com.reactor.rust.annotations.Request;

@CompiledJson
@Request
public record OrderCreateRequest(
    int orderId,
    double amount
) {}
