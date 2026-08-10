package com.reactor.rust.example.config;

import com.reactor.rust.annotations.ConfigDefault;
import com.reactor.rust.annotations.ConfigName;
import com.reactor.rust.annotations.ConfigurationProperties;

/** Immutable sample configuration constructed by the generated startup descriptor. */
@ConfigurationProperties("")
public record ServerConfig(
        @ConfigName("server.port") int port,
        @ConfigName("server.host") String host,
        @ConfigName("server.timeout") @ConfigDefault("30000") int timeoutMs,
        @ConfigName("db.pool.size") int dbPoolSize) {}
