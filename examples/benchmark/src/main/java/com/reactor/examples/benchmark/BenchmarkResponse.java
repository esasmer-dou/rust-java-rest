package com.reactor.examples.benchmark;

import com.reactor.rust.annotations.Response;

@Response
public record BenchmarkResponse(long id, String status, boolean active) {}
