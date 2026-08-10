package com.reactor.rust.smoke;

import com.dslplatform.json.CompiledJson;
import com.reactor.rust.annotations.Response;

@Response
@CompiledJson
public record SmokeResponse(long id, String status) {}
