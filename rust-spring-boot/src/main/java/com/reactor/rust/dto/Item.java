package com.reactor.rust.dto;

import com.dslplatform.json.CompiledJson;
import com.reactor.rust.annotations.Request;

@CompiledJson
@Request
public record Item(
    String name,
    double price
) {}
