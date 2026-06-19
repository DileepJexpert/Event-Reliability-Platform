package com.brod.starter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Default, process-local {@link IdempotencyStore}. Suitable for tests and single-instance use; replace
 * with a shared-store-backed bean for production (so dedup survives restarts and spans instances).
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private static final Object PRESENT = new Object();

    private final ConcurrentMap<String, Object> seen = new ConcurrentHashMap<>();

    @Override
    public boolean markProcessed(String key) {
        return seen.putIfAbsent(key, PRESENT) == null;
    }

    @Override
    public boolean isProcessed(String key) {
        return seen.containsKey(key);
    }
}
