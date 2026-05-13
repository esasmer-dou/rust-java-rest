package com.reactor.rust.dubbo.sample;

/**
 * Minimal sample Dubbo contract used by the framework's example consumer.
 *
 * <p>The interface is intentionally kept in this module so the framework package
 * does not require a separate sample-api artifact during release builds.</p>
 */
public interface NestedCatalogService {
    byte[] getNestedCatalogJson();
}
