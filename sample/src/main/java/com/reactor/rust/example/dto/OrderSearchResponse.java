package com.reactor.rust.example.dto;

import com.dslplatform.json.CompiledJson;
import com.reactor.rust.annotations.Response;
import com.reactor.rust.annotations.GenerateDirectJsonWriter;

@CompiledJson
@Response
@GenerateDirectJsonWriter
public record OrderSearchResponse(
    String status,
    String page
) {}
