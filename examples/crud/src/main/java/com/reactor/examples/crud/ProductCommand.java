package com.reactor.examples.crud;

import com.dslplatform.json.CompiledJson;
import com.reactor.rust.annotations.NotBlank;
import com.reactor.rust.annotations.Request;

@CompiledJson
@Request
public record ProductCommand(
        @NotBlank(message = "name is required") String name,
        long priceCents) {}
