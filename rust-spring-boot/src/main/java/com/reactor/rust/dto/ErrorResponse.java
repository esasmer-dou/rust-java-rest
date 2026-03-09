package com.reactor.rust.dto;

import com.dslplatform.json.CompiledJson;
import com.reactor.rust.annotations.Response;

@CompiledJson
@Response
public record ErrorResponse(
    String error
) {}
