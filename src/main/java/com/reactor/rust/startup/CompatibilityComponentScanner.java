package com.reactor.rust.startup;

import com.reactor.rust.di.BeanContainer;

/** Optional SPI implemented by the separate compatibility artifact. */
public interface CompatibilityComponentScanner {

    void scan(String packageName, BeanContainer container);
}
