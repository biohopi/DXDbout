# Gemma 4 Local GGUF Chat Android App

A production-ready Android application for running Gemma 4 (and other GGUF models) locally on-device with `llama.cpp` native Vulkan/NEON acceleration, featuring optional web-search tool calling via any OpenAI-compatible API endpoint.

---

## Features

1. **Local GGUF Inference Engine:**
   - Powered by official native `llama.cpp` with Vulkan GPU acceleration and multi-threaded CPU NEON fallback.
   - Optimized mmap loading, KV cache management, and token-by-token streaming.
   - Built for 6-bit quantized Gemma 4 (`HauhauCS/Gemma-4-E2B-Uncensored-HauhauCS-Aggressive` Q6_K) and arbitrary GGUF models.

2. **Web-When-Needed Tool Calling:**
   - Supports 3 modes: `Local Only`, `Web Only`, and `Local with Web Tool Assistance`.
   - In hybrid mode, detects when queries require factual/current context, calls an OpenAI-compatible `/v1/chat/completions` endpoint, feeds the web context to Gemma 4, and streams the answer locally.

3. **Material 3 UI:**
   - Built with Jetpack Compose.
   - Real-time token streaming, copy/clear actions, and live status bar displaying loaded model info, backend (Vulkan/CPU), RAM usage, and generation speed (tokens/sec).
   - Storage Access Framework (SAF) file picker for selecting `.gguf` files from Downloads or external storage.

---

## Project Structure & Architecture

```
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── cpp/
│       │   │   ├── CMakeLists.txt
│       │   │   ├── llama_jni.cpp (JNI Bridge)
│       │   │   └── llama.cpp/ (Git submodule/source)
│       │   ├── java/com/turbodns/gguf/chat/
│       │   │   ├── MainActivity.kt
│       │   │   ├── data/ (AppSettings, SettingsRepository, WebClient, ToolCallingRouter)
│       │   │   ├── engine/ (LlamaNative, ModelEngine, GenerationStats)
│       │   │   └── ui/ (ChatViewModel, MainScreen)
│       │   └── AndroidManifest.xml
│       └── test/ (Unit tests)
└── gradle.properties
```

---

## How to Build the APK

### Prerequisites
- Android Studio Ladybug or later / Gradle 8.8+
- Android SDK API 35 (`compileSdk = 35`, `minSdk = 24`)
- Android NDK `27.2.12479018`
- CMake `3.22.1`

### Build Steps
1. Clone the repository and submodules:
   ```bash
   git clone --recursive <repo-url>
   ```
2. Build the debug APK via Gradle:
   ```bash
   ./gradlew assembleDebug
   ```
3. Locate the generated APK at:
   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

---

## Placing the Model on Device

1. Download the Gemma 4 Q6_K GGUF model:
   - File name: `gemma-4-e2b-uncensored-q6_k.gguf`
2. Copy the `.gguf` file onto your device's Downloads or Internal Storage.
3. Open the app, tap the **Folder icon** in the top bar, and pick the `.gguf` file.
4. (Optional) Alternatively, place the `.gguf` file inside `app/src/main/assets/gemma-4-e2b-q6.gguf` before compiling to bundle it automatically.
