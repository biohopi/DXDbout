package com.turbodns.changer.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import com.turbodns.changer.engine.ParallelDnsResolver;
import com.turbodns.changer.model.DnsPreset;
import com.turbodns.changer.storage.PresetStorage;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class TurboVpnService extends VpnService implements Runnable {

    public static final String ACTION_START = "com.turbodns.changer.START";
    public static final String ACTION_STOP = "com.turbodns.changer.STOP";
    private static final String CHANNEL_ID = "turbo_dns_channel";

    private static ParallelDnsResolver activeResolver;
    private static boolean isRunning = false;

    private ParcelFileDescriptor vpnInterface;
    private Thread vpnThread;

    public static ParallelDnsResolver getActiveResolver() {
        return activeResolver;
    }

    public static boolean isServiceRunning() {
        return isRunning;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopVpn();
            return START_NOT_STICKY;
        }

        startForegroundNotification();
        setupAndStartVpn();

        return START_STICKY;
    }

    private void startForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "TurboDNS Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            if (nm != null) nm.createNotificationChannel(channel);
        }

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        Notification notification = builder
                .setContentTitle("TurboDNS Active")
                .setContentText("Parallel DNS Racing engine is running system-wide.")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();

        startForeground(1001, notification);
    }

    private void setupAndStartVpn() {
        PresetStorage storage = new PresetStorage(this);
        DnsPreset activePreset = storage.getActivePreset();

        List<String> servers = (activePreset != null && !activePreset.getDnsServers().isEmpty())
                ? activePreset.getDnsServers()
                : new ArrayList<String>();

        activeResolver = new ParallelDnsResolver(servers);

        try {
            Builder builder = new Builder();
            builder.setSession("TurboDNS");
            builder.addAddress("10.1.10.1", 24);
            builder.addAddress("fd00::1", 64);

            // Forward system DNS queries through VPN interface
            builder.addDnsServer("10.1.10.2");
            builder.addRoute("10.1.10.2", 32);

            builder.addDnsServer("fd00::2");
            builder.addRoute("fd00::2", 128);

            vpnInterface = builder.establish();
            isRunning = true;

            vpnThread = new Thread(this, "TurboVpnThread");
            vpnThread.start();
        } catch (Exception e) {
            e.printStackTrace();
            stopVpn();
        }
    }

    @Override
    public void run() {
        FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());

        byte[] packetBuffer = new byte[32767];

        while (isRunning && !Thread.currentThread().isInterrupted()) {
            try {
                int length = in.read(packetBuffer);
                if (length > 0) {
                    processPacket(packetBuffer, length, out);
                }
            } catch (IOException e) {
                if (!isRunning) break;
            }
        }
    }

    private void processPacket(byte[] packet, int length, FileOutputStream out) {
        if (length < 28) return;

        int ipVersion = (packet[0] >> 4) & 0x0F;
        int ipHeaderLen = 0;
        int transportProtocol = 0;

        if (ipVersion == 4) {
            ipHeaderLen = (packet[0] & 0x0F) * 4;
            transportProtocol = packet[9] & 0xFF;
        } else if (ipVersion == 6) {
            ipHeaderLen = 40;
            transportProtocol = packet[6] & 0xFF;
        }

        if (transportProtocol != 17) return; // UDP

        int udpHeaderStart = ipHeaderLen;
        if (length < udpHeaderStart + 8) return;

        int destPort = ((packet[udpHeaderStart + 2] & 0xFF) << 8) | (packet[udpHeaderStart + 3] & 0xFF);
        if (destPort != 53) return; // DNS port

        int dnsPayloadStart = udpHeaderStart + 8;
        int dnsPayloadLen = length - dnsPayloadStart;
        if (dnsPayloadLen <= 0) return;

        byte[] dnsRequest = new byte[dnsPayloadLen];
        System.arraycopy(packet, dnsPayloadStart, dnsRequest, 0, dnsPayloadLen);

        if (activeResolver != null) {
            byte[] dnsResponse = activeResolver.resolve(dnsRequest);
            if (dnsResponse != null) {
                byte[] replyIpPacket = buildUdpReplyPacket(packet, ipVersion, ipHeaderLen, udpHeaderStart, dnsResponse);
                if (replyIpPacket != null) {
                    try {
                        out.write(replyIpPacket);
                        out.flush();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private byte[] buildUdpReplyPacket(byte[] origPacket, int ipVersion, int ipHeaderLen, int udpHeaderStart, byte[] dnsResponse) {
        int responseLen = dnsResponse.length;
        int totalLen = ipHeaderLen + 8 + responseLen;
        byte[] reply = new byte[totalLen];

        if (ipVersion == 4) {
            System.arraycopy(origPacket, 0, reply, 0, ipHeaderLen);
            reply[2] = (byte) ((totalLen >> 8) & 0xFF);
            reply[3] = (byte) (totalLen & 0xFF);

            // Swap IPs
            System.arraycopy(origPacket, 12, reply, 16, 4); // Orig src -> reply dst
            System.arraycopy(origPacket, 16, reply, 12, 4); // Orig dst -> reply src

            // Recalculate IPv4 Header Checksum
            reply[10] = 0;
            reply[11] = 0;
            int checksum = calculateIpChecksum(reply, ipHeaderLen);
            reply[10] = (byte) ((checksum >> 8) & 0xFF);
            reply[11] = (byte) (checksum & 0xFF);
        } else if (ipVersion == 6) {
            System.arraycopy(origPacket, 0, reply, 0, ipHeaderLen);
            int payloadLen = 8 + responseLen;
            reply[4] = (byte) ((payloadLen >> 8) & 0xFF);
            reply[5] = (byte) (payloadLen & 0xFF);

            // Swap IPv6 addresses
            System.arraycopy(origPacket, 8, reply, 24, 16);
            System.arraycopy(origPacket, 24, reply, 8, 16);
        }

        // Swap Ports in UDP Header
        System.arraycopy(origPacket, udpHeaderStart + 2, reply, ipHeaderLen, 2); // Orig dst port -> reply src port
        System.arraycopy(origPacket, udpHeaderStart, reply, ipHeaderLen + 2, 2); // Orig src port -> reply dst port

        int udpLen = 8 + responseLen;
        reply[ipHeaderLen + 4] = (byte) ((udpLen >> 8) & 0xFF);
        reply[ipHeaderLen + 5] = (byte) (udpLen & 0xFF);

        // Copy DNS payload
        System.arraycopy(dnsResponse, 0, reply, ipHeaderLen + 8, responseLen);

        // Calculate and set proper UDP Checksum
        reply[ipHeaderLen + 6] = 0;
        reply[ipHeaderLen + 7] = 0;
        int udpChecksum = calculateUdpChecksum(reply, ipVersion, ipHeaderLen, udpLen);
        reply[ipHeaderLen + 6] = (byte) ((udpChecksum >> 8) & 0xFF);
        reply[ipHeaderLen + 7] = (byte) (udpChecksum & 0xFF);

        return reply;
    }

    private int calculateUdpChecksum(byte[] packet, int ipVersion, int ipHeaderLen, int udpLen) {
        int sum = 0;
        if (ipVersion == 4) {
            // IPv4 Pseudo Header: Src IP (4B), Dst IP (4B), Zero (1B), Protocol (1B=17), UDP Length (2B)
            for (int i = 12; i < 20; i += 2) {
                sum += ((packet[i] & 0xFF) << 8) | (packet[i + 1] & 0xFF);
            }
            sum += 17; // UDP protocol number
            sum += udpLen;
        } else if (ipVersion == 6) {
            // IPv6 Pseudo Header: Src IP (16B), Dst IP (16B), Upper-Layer Packet Length (4B), Next Header (4B = 17)
            for (int i = 8; i < 40; i += 2) {
                sum += ((packet[i] & 0xFF) << 8) | (packet[i + 1] & 0xFF);
            }
            sum += (udpLen >> 16) & 0xFFFF;
            sum += udpLen & 0xFFFF;
            sum += 17;
        }

        // UDP Header + Payload
        int udpStart = ipHeaderLen;
        int length = udpLen;
        int i = udpStart;

        while (length > 1) {
            sum += ((packet[i] & 0xFF) << 8) | (packet[i + 1] & 0xFF);
            i += 2;
            length -= 2;
        }
        if (length > 0) {
            sum += (packet[i] & 0xFF) << 8;
        }

        while ((sum >> 16) > 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }

        int checksum = ~sum & 0xFFFF;
        if (checksum == 0 && ipVersion == 6) {
            checksum = 0xFFFF; // RFC 2460: If calculated checksum is 0 for IPv6 UDP, transmit 0xFFFF
        }
        return checksum;
    }

    private int calculateIpChecksum(byte[] buf, int length) {
        int sum = 0;
        int i = 0;
        while (length > 1) {
            sum += ((buf[i] & 0xFF) << 8) | (buf[i + 1] & 0xFF);
            i += 2;
            length -= 2;
        }
        if (length > 0) {
            sum += (buf[i] & 0xFF) << 8;
        }
        while ((sum >> 16) > 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return ~sum & 0xFFFF;
    }

    private void stopVpn() {
        isRunning = false;
        if (activeResolver != null) {
            activeResolver.shutdown();
            activeResolver = null;
        }
        if (vpnThread != null) {
            vpnThread.interrupt();
        }
        try {
            if (vpnInterface != null) {
                vpnInterface.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }
}
