package com.reactor.rust.util;

/**
 * @deprecated Use {@link RequestValueMap}. This compatibility name is retained so existing
 * applications do not break while framework internals use the domain-specific type.
 */
@Deprecated(forRemoval = false)
public final class FastMapV2 extends RequestValueMap {

    private static final ThreadLocal<FastMapV2> POOL = ThreadLocal.withInitial(FastMapV2::new);

    public FastMapV2() {
        super();
    }

    public FastMapV2(int capacity) {
        super(capacity);
    }

    public static FastMapV2 acquire() {
        FastMapV2 map = POOL.get();
        map.clear();
        return map;
    }
}
