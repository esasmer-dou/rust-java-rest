package com.reactor.rust.bridge;

/**
 * Supplies build-time-equivalent route invokers for framework handlers created at runtime.
 *
 * <p>The hook runs once while the mutable handler registry is being assembled. It is not part of
 * request dispatch.</p>
 */
public interface GeneratedRouteContributor {

    void registerGeneratedRouteInvokers();
}
