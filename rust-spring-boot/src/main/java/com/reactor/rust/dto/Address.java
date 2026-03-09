package com.reactor.rust.dto;

import com.dslplatform.json.CompiledJson;
import com.reactor.rust.annotations.Request;
import com.reactor.rust.annotations.Field;

@CompiledJson
@Request
public record Address(
    String city,
    String street
) {}
