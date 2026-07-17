package com.reactor.examples.crud;

import com.dslplatform.json.CompiledJson;
import com.reactor.rust.annotations.GenerateDirectJsonWriter;

@CompiledJson
@GenerateDirectJsonWriter
public record Product(long id, String name, long priceCents) {}
