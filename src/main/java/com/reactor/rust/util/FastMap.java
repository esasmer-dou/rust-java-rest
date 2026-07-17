package com.reactor.rust.util;

/**
 * Small array-backed map for request parameter parsing.
 * Reuses storage instead of creating a HashMap for every parse.
 *
 * Performance characteristics:
 * - Reuses internal arrays after capacity stabilizes; growth can allocate
 * - O(n) lookup (but n is typically &lt; 10 for HTTP params)
 * - Not thread-safe; intended for thread-confined reuse
 *
 * Usage:
 *   FastMap map = FastMap.acquire();
 *   try {
 *       map.put("key", "value");
 *       String val = map.get("key");
 *   } finally {
 *       map.clear(); // Reset for next use
 *   }
 */
public final class FastMap {

    // Initial capacity for typical HTTP request (path params + query params)
    private static final int DEFAULT_CAPACITY = 16;
    private static final int MAX_RETAINED_CAPACITY = 64;

    // Thread-local holder used to reuse the backing arrays
    private static final ThreadLocal<FastMap> POOL =
        ThreadLocal.withInitial(FastMap::new);

    // Storage arrays (grown as needed)
    private String[] keys;
    private String[] values;
    private int size;

    /**
     * Get a FastMap from thread-local pool.
     * MUST call clear() when done to reset for next use.
     */
    public static FastMap acquire() {
        FastMap map = POOL.get();
        map.size = 0; // Reset size but keep arrays
        return map;
    }

    /**
     * Create a new FastMap with default capacity.
     */
    public FastMap() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Create a new FastMap with specified capacity.
     */
    public FastMap(int capacity) {
        this.keys = new String[capacity];
        this.values = new String[capacity];
        this.size = 0;
    }

    /**
     * Put a key-value pair. Overwrites if key exists.
     * Grows arrays if needed (amortized O(1)).
     */
    public void put(String key, String value) {
        // Check if key exists
        for (int i = 0; i < size; i++) {
            if (keys[i].equals(key)) {
                values[i] = value;
                return;
            }
        }

        // Grow arrays if needed
        if (size >= keys.length) {
            int newCapacity = keys.length * 2;
            String[] newKeys = new String[newCapacity];
            String[] newValues = new String[newCapacity];
            System.arraycopy(keys, 0, newKeys, 0, size);
            System.arraycopy(values, 0, newValues, 0, size);
            keys = newKeys;
            values = newValues;
        }

        // Add new entry
        keys[size] = key;
        values[size] = value;
        size++;
    }

    /**
     * Get value for key, or null if not found.
     * O(n) but n is typically small (&lt; 10).
     */
    public String get(String key) {
        for (int i = 0; i < size; i++) {
            if (keys[i].equals(key)) {
                return values[i];
            }
        }
        return null;
    }

    /**
     * Get value for key ignoring case, or null if not found.
     */
    public String getIgnoreCase(String key) {
        for (int i = 0; i < size; i++) {
            if (keys[i].equalsIgnoreCase(key)) {
                return values[i];
            }
        }
        return null;
    }

    /**
     * Check if key exists.
     */
    public boolean containsKey(String key) {
        for (int i = 0; i < size; i++) {
            if (keys[i].equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get current size.
     */
    public int size() {
        return size;
    }

    /**
     * Check if empty.
     */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * Clear all entries (resets size, keeps arrays).
     * Also sets all values to null to help GC.
     */
    public void clear() {
        if (keys.length > MAX_RETAINED_CAPACITY) {
            keys = new String[DEFAULT_CAPACITY];
            values = new String[DEFAULT_CAPACITY];
            size = 0;
            return;
        }
        for (int i = 0; i < size; i++) {
            keys[i] = null;
            values[i] = null;
        }
        size = 0;
    }

    int retainedCapacity() {
        return keys.length;
    }

    /**
     * Get key at index (for iteration).
     */
    public String getKeyAt(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        return keys[index];
    }

    /**
     * Get value at index (for iteration).
     */
    public String getValueAt(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        return values[index];
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < size; i++) {
            if (i > 0) sb.append(", ");
            sb.append(keys[i]).append("=").append(values[i]);
        }
        sb.append("}");
        return sb.toString();
    }
}
