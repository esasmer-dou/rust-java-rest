package com.reactor.rust.health;

/** Synchronous dependency probe executed only when readiness is requested. */
@FunctionalInterface
public interface DependencyProbe {
    boolean ready() throws Exception;
}
