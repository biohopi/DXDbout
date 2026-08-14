package com.turbodns.changer.engine;

import java.util.LinkedHashMap;
import java.util.Map;

public class DnsCache {
    private static class CacheEntry {
        byte[] response;
        long expireTimeMs;

        CacheEntry(byte[] response, long ttlMs) {
            this.response = response;
            this.expireTimeMs = System.currentTimeMillis() + ttlMs;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expireTimeMs;
        }
    }

    private final int capacity;
    private final Map<String, CacheEntry> cacheMap;
    private static final long DEFAULT_TTL_MS = 60_000; // 60 seconds default cache TTL

    public DnsCache(final int capacity) {
        this.capacity = capacity;
        this.cacheMap = new LinkedHashMap<String, CacheEntry>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                return size() > capacity;
            }
        };
    }

    public synchronized void put(String key, byte[] response) {
        if (key == null || response == null) return;
        cacheMap.put(key, new CacheEntry(response.clone(), DEFAULT_TTL_MS));
    }

    public synchronized byte[] get(String key) {
        if (key == null) return null;
        CacheEntry entry = cacheMap.get(key);
        if (entry == null) return null;
        if (entry.isExpired()) {
            cacheMap.remove(key);
            return null;
        }
        return entry.response.clone();
    }

    public synchronized void clear() {
        cacheMap.clear();
    }
}
