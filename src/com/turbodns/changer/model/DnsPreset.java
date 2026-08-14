package com.turbodns.changer.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class DnsPreset implements Serializable {
    private String id;
    private String name;
    private List<String> dnsServers;

    public DnsPreset() {
        this.id = String.valueOf(System.currentTimeMillis());
        this.name = "Custom Preset";
        this.dnsServers = new ArrayList<>();
    }

    public DnsPreset(String id, String name, List<String> dnsServers) {
        this.id = id;
        this.name = name;
        this.dnsServers = dnsServers != null ? dnsServers : new ArrayList<>();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getDnsServers() {
        return dnsServers;
    }

    public void setDnsServers(List<String> dnsServers) {
        this.dnsServers = dnsServers;
    }

    public void addDnsServer(String ipAddress) {
        if (ipAddress != null && !ipAddress.trim().isEmpty()) {
            this.dnsServers.add(ipAddress.trim());
        }
    }

    @Override
    public String toString() {
        return name + " (" + dnsServers.size() + " DNS servers)";
    }
}
