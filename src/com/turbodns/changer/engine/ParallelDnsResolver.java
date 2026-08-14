package com.turbodns.changer.engine;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ParallelDnsResolver {

    private final List<String> dnsServers = new ArrayList<>();
    private final DnsCache dnsCache = new DnsCache(500);
    private final DnsServerStats stats = new DnsServerStats();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public ParallelDnsResolver(List<String> dnsServers) {
        updateDnsServers(dnsServers);
    }

    public synchronized void updateDnsServers(List<String> servers) {
        this.dnsServers.clear();
        if (servers != null) {
            this.dnsServers.addAll(servers);
        }
    }

    public DnsServerStats getStats() {
        return stats;
    }

    public byte[] resolve(byte[] requestPacket) {
        if (requestPacket == null || requestPacket.length == 0) return null;

        int origTxId = DnsPacket.getTransactionId(requestPacket);
        String questionKey = DnsPacket.getQuestionKey(requestPacket);

        // 1. Check Smart LRU Cache (<1ms resolution)
        if (questionKey != null) {
            byte[] cachedResponse = dnsCache.get(questionKey);
            if (cachedResponse != null) {
                return DnsPacket.rewriteTransactionId(cachedResponse, origTxId);
            }
        }

        List<String> activeServers;
        synchronized (this) {
            activeServers = new ArrayList<>(dnsServers);
        }

        if (activeServers.isEmpty()) {
            return null;
        }

        // Adaptive Health Scoring: sort servers by latency/jitter/health score
        Collections.sort(activeServers, new Comparator<String>() {
            @Override
            public int compare(String s1, String s2) {
                double score1 = stats.getOrCreate(s1).getScore();
                double score2 = stats.getOrCreate(s2).getScore();
                return Double.compare(score1, score2);
            }
        });

        // 2. Parallel Dual-Stack DNS Racing
        final ArrayBlockingQueue<byte[]> winnerQueue = new ArrayBlockingQueue<>(1);

        for (final String serverIp : activeServers) {
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    long startTime = System.currentTimeMillis();
                    try (DatagramSocket socket = new DatagramSocket()) {
                        socket.setSoTimeout(3000); // 3 sec timeout per query
                        InetAddress address = InetAddress.getByName(serverIp);
                        DatagramPacket sendPacket = new DatagramPacket(requestPacket, requestPacket.length, address, 53);
                        socket.send(sendPacket);

                        byte[] buffer = new byte[1500];
                        DatagramPacket recvPacket = new DatagramPacket(buffer, buffer.length);
                        socket.receive(recvPacket);

                        long rtt = System.currentTimeMillis() - startTime;
                        stats.recordSuccess(serverIp, rtt);

                        byte[] response = new byte[recvPacket.getLength()];
                        System.arraycopy(recvPacket.getData(), 0, response, 0, response.length);

                        winnerQueue.offer(response);
                    } catch (Exception e) {
                        stats.recordFailure(serverIp);
                    }
                }
            });
        }

        try {
            byte[] winningResponse = winnerQueue.poll(3000, TimeUnit.MILLISECONDS);
            if (winningResponse != null) {
                if (questionKey != null) {
                    dnsCache.put(questionKey, winningResponse);
                }
                return DnsPacket.rewriteTransactionId(winningResponse, origTxId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return null;
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
