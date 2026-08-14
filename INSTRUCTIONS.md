# TurboDNS Changer - Build, Import & Project Setup Guide

`TurboDNS Changer` is a high-performance, system-wide parallel DNS optimizer for Android designed specifically for build systems without Gradle.

---

## 📌 Project Specifications

| Setting | Value |
|---|---|
| **App Name** | `TurboDNS Changer` |
| **Package Name** | `com.turbodns.changer` |
| **Target SDK** | `35` (Android 15 HyperOS 2 / Xiaomi Redmi 13c) |
| **Min SDK** | `26` (Android 8.0) |
| **Compile SDK** | `35` (Android 15) |
| **Java Compatibility** | `Java 1.8+` |
| **Build Configuration File** | `module.toml` & `project.properties` |

---

## 📁 Directory Structure

```
.
├── module.toml                     # Tab / non-Gradle IDE build configuration
├── project.properties              # Standard Android SDK target configuration
├── AndroidManifest.xml             # Application manifest & system permissions
├── res/                            # UI Resources
│   ├── values/
│   │   └── strings.xml
│   └── layout/
│       ├── activity_main.xml       # Main dashboard layout
│       └── activity_preset_editor.xml
└── src/                            # Java Source Code
    └── com/turbodns/changer/
        ├── model/
        │   └── DnsPreset.java      # Preset data model (IPv4 & IPv6)
        ├── storage/
        │   └── PresetStorage.java  # Persistent SharedPreferences/JSON storage
        ├── engine/
        │   ├── DnsPacket.java      # UDP packet parser/builder
        │   ├── DnsCache.java       # Sub-millisecond local LRU DNS cache
        │   ├── DnsServerStats.java # Real-time latency, ping & jitter tracker
        │   ├── ParallelDnsResolver.java # Dual-stack parallel racing engine
        │   └── RootDnsEngine.java  # Root su direct netd & system property tuner
        ├── service/
        │   └── TurboVpnService.java# System-wide VpnService packet interceptor
        └── ui/
            ├── MainActivity.java    # Main UI & stats dashboard
            └── PresetEditorActivity.java # Custom preset manager
```

---

## 🚀 How to Import and Build in Non-Gradle Android IDEs

1. **Importing into your Android IDE:**
   - Open your Android IDE (e.g. AIDE, Android Code Editor, or custom mobile IDE).
   - Choose **Import / Open Existing Android Project**.
   - Select the root directory containing `module.toml` and `AndroidManifest.xml`.

2. **Building the APK:**
   - Ensure the compile target is set to **Android 15 (API 35)**.
   - Run **Build / Compile APK**.
   - The IDE will use `module.toml` and `project.properties` to compile the Java sources in `src/` and bundle resources in `res/`.

3. **Installing & Using on Device (Xiaomi Redmi 13c / Android 15 HyperOS 2):**
   - Install the compiled `.apk`.
   - **VpnService Mode:** Tap "Start Service" and approve the local VPN prompt.
   - **Root Mode (Optional):** Select "Root Mode", tap "Start Service", and grant Superuser access when prompted.
   - **Managing Presets:** Click "Manage Presets" to add custom IPv4 & IPv6 DNS server addresses.
