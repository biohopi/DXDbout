package com.turbodns.changer.engine;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DnsServerStats {

    public static class Stat {
        public final String serverIp;
        public long totalQueries = 0;
        public long successfulQueries = 0;
        public long failedQueries = 0;
        public long lastRttMs = 0;
        public long minRttMs = Long.MAX_VALUE;
        public double avgRttMs = 0;
        public double jitterMs = 0;
        public long lastSeenMs = 0;

        public Stat(String serverIp) {
            this.serverIp = serverIp;
        }

        public synchronized void recordSuccess(long rttMs) {
            totalQueries++;
            successfulQueries++;
            lastRttMs = rttMs;
            if (rttMs < minRttMs) {
                minRttMs = rttMs;
            }
            if (avgRttMs == 0) {
                avgRttMs = rttMs;
            } else {
                double prevAvg = avgRttMs;
                avgRttMs = avgRttMs + (rttMs - avgRttMs) / Math.min(successfulQueries, 20);
                double diff = Math.abs(rttMs - prevAvg);
                jitterMs = jitterMs + (diff - jitterMs) / Math.min(successfulQueries, 20);
            }
            lastSeenMs = System.currentTimeMillis();
        }

        public synchronized void recordFailure() {
            totalQueries++;
            failedQueries++;
            lastSeenMs = System.currentTimeMillis();
        }

        public synchronized double getScore() {
            if (totalQueries == 0) return 100.0; // Unchecked, high priority
            double lossRatio = (double) failedQueries / totalQueries;
            if (lossRatio >= 0.8) return 99999.0; // Severely penalize unresponsive servers
            return avgRttMs + (jitterMs * 1.5) + (lossRatio * 200);
        }

        @Override
        public String toString() {
            if (totalQueries == 0) {
                return String.format("%s -> Idle", serverIp);
            }
            return String.format("%s -> Ping: %d ms | Avg: %.1f ms | Jitter: %.1f ms | Loss: %d/%d",
                    serverIp, lastRttMs, avgRttMs, jitterMs, failedQueries, totalQueries);
        }
    }

    private final Map<String, Stat> statsMap = new ConcurrentHashMap<>();

    public Stat getOrCreate(String serverIp) {
        return statsMap.computeIfAbsent(serverIp, Stat::new);
    }

    public void recordSuccess(String serverIp, long rttMs) {
        getOrCreate(serverIp).recordSuccess(rttMs);
    }

    public void recordFailure(String serverIp) {
        getOrCreate(serverIp).recordFailure();
    }

    public Map<String, Stat> getAllStats() {
        return Collections.unmodifiableMap(statsMap);
    }

    public void clear() {
        statsMap.clear();
    }
}
