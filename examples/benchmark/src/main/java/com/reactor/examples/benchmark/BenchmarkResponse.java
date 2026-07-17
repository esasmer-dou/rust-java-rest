package com.reactor.examples.benchmark;

import com.reactor.rust.annotations.GenerateDirectJsonWriter;

@GenerateDirectJsonWriter
public record BenchmarkResponse(long id, String status, boolean active) {}
