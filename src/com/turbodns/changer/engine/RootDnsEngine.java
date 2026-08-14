package com.turbodns.changer.engine;

import java.io.DataOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class RootDnsEngine {

    public static boolean isRootAvailable() {
        try {
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            os.writeBytes("id\n");
            os.writeBytes("exit\n");
            os.flush();
            process.waitFor();
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean applyRootDnsSettings(List<String> dnsServers) {
        if (dnsServers == null || dnsServers.isEmpty()) return false;

        List<String> commands = new ArrayList<>();

        // 1. Set global system DNS properties
        int index = 1;
        for (String ip : dnsServers) {
            commands.add("setprop net.dns" + index + " " + ip);
            index++;
            if (index > 4) break; // System properties typically support net.dns1..net.dns4
        }

        // 2. Set modern netd system properties for Android 10+
        commands.add("setprop net.eth0.dns1 " + dnsServers.get(0));
        commands.add("setprop net.wlan0.dns1 " + dnsServers.get(0));
        if (dnsServers.size() > 1) {
            commands.add("setprop net.eth0.dns2 " + dnsServers.get(1));
            commands.add("setprop net.wlan0.dns2 " + dnsServers.get(1));
        }

        // 3. System kernel socket tuning for latency & throughput optimization
        commands.add("sysctl -w net.core.rmem_max=2097152");
        commands.add("sysctl -w net.core.wmem_max=2097152");

        // 4. Flush Android native resolver cache
        commands.add("ndc resolver flushdefaultif");
        commands.add("ndc resolver flushif wlan0");
        commands.add("ndc resolver flushif rmnet_data0");

        return executeSuCommands(commands);
    }

    public static boolean restoreRootDnsSettings() {
        List<String> commands = new ArrayList<>();
        commands.add("ndc resolver flushdefaultif");
        return executeSuCommands(commands);
    }

    private static boolean executeSuCommands(List<String> commands) {
        try {
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            for (String cmd : commands) {
                os.writeBytes(cmd + "\n");
            }
            os.writeBytes("exit\n");
            os.flush();
            process.waitFor();
            return process.exitValue() == 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
