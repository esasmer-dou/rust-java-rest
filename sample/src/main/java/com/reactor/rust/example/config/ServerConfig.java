package com.reactor.rust.example.config;

import com.reactor.rust.annotations.RustProperty;
import com.reactor.rust.di.annotation.Component;

/**
 * Example: @RustProperty injection (Constraint #8)
 *
 * Properties from rust-spring.properties are injected at startup.
 */
@Component
public class ServerConfig {

    @RustProperty("server.port")
    private int port;

    @RustProperty("server.host")
    private String host;

    @RustProperty(value = "server.timeout", defaultValue = "30000")
    private int timeoutMs;

    @RustProperty("db.pool.size")
    private int dbPoolSize;

    // Getters
    public int getPort() {
        return port;
    }

    public String getHost() {
        return host;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public int getDbPoolSize() {
        return dbPoolSize;
    }

    @Override
    public String toString() {
        return "ServerConfig{" +
                "port=" + port +
                ", host='" + host + '\'' +
                ", timeoutMs=" + timeoutMs +
                ", dbPoolSize=" + dbPoolSize +
                '}';
    }
}
