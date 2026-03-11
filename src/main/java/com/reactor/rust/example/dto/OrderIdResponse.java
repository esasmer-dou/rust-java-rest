package com.reactor.rust.example.dto;

import com.dslplatform.json.CompiledJson;
import com.reactor.rust.annotations.Response;

@CompiledJson
@Response
public record OrderIdResponse(
    String id
) {}
