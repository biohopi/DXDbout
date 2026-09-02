package com.turbodns.gguf.chat.engine

object LlamaNative {
    init {
        System.loadLibrary("llama_jni")
        nativeInitBackend()
    }

    interface StreamCallback {
        fun onToken(token: String)
        fun isCanceled(): Boolean
    }

    external fun nativeInitBackend()

    external fun nativeLoadModel(
        modelPath: String,
        useVulkan: Boolean,
        nGpuLayers: Int
    ): Long

    external fun nativeCreateContext(
        modelHandle: Long,
        nCtx: Int,
        nThreads: Int
    ): Long

    external fun nativeGenerateStream(
        ctxHandle: Long,
        prompt: String,
        maxTokens: Int,
        temp: Float,
        topP: Float,
        repeatPenalty: Float,
        callback: StreamCallback
    ): Int

    external fun nativeFreeContext(ctxHandle: Long)

    external fun nativeFreeModel(modelHandle: Long)
}
