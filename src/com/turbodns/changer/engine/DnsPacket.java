package com.turbodns.changer.engine;

import java.nio.ByteBuffer;

public class DnsPacket {

    public static int getTransactionId(byte[] rawDns) {
        if (rawDns == null || rawDns.length < 2) return -1;
        return ((rawDns[0] & 0xFF) << 8) | (rawDns[1] & 0xFF);
    }

    public static String getQuestionKey(byte[] rawDns) {
        if (rawDns == null || rawDns.length < 12) return null;
        try {
            StringBuilder name = new StringBuilder();
            int pos = 12; // Skip DNS header (12 bytes)
            while (pos < rawDns.length) {
                int len = rawDns[pos] & 0xFF;
                if (len == 0) {
                    pos++;
                    break;
                }
                if (name.length() > 0) name.append(".");
                pos++;
                if (pos + len > rawDns.length) break;
                name.append(new String(rawDns, pos, len));
                pos += len;
            }
            if (pos + 4 <= rawDns.length) {
                int qtype = ((rawDns[pos] & 0xFF) << 8) | (rawDns[pos + 1] & 0xFF);
                int qclass = ((rawDns[pos + 2] & 0xFF) << 8) | (rawDns[pos + 3] & 0xFF);
                return name.toString().toLowerCase() + ":" + qtype + ":" + qclass;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static byte[] rewriteTransactionId(byte[] responsePacket, int originalTransactionId) {
        if (responsePacket == null || responsePacket.length < 2) return responsePacket;
        byte[] copy = responsePacket.clone();
        copy[0] = (byte) ((originalTransactionId >> 8) & 0xFF);
        copy[1] = (byte) (originalTransactionId & 0xFF);
        return copy;
    }
}
