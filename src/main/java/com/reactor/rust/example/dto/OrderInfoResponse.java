package com.reactor.rust.example.dto;

import com.dslplatform.json.CompiledJson;
import com.reactor.rust.annotations.Response;

@CompiledJson
@Response
public record OrderInfoResponse(
    String orderId,
    String status,
    String customerName
) {}
