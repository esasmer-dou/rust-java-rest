package com.reactor.rust.example.dto;

import com.dslplatform.json.CompiledJson;
import com.reactor.rust.annotations.Response;

@CompiledJson
@Response
public record OrderSearchResponse(
    String status,
    String page
) {}
