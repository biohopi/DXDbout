package com.turbodns.gguf.chat.engine

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

data class GenerationStats(
    val tokensGenerated: Int,
    val totalTimeMs: Long,
    val tokensPerSecond: Float
)

sealed class ModelLoadState {
    object Idle : ModelLoadState()
    data class Loading(val message: String) : ModelLoadState()
    data class Loaded(
        val modelPath: String,
        val modelName: String,
        val quantization: String,
        val isVulkan: Boolean
    ) : ModelLoadState()
    data class Error(val error: String) : ModelLoadState()
}

class ModelEngine(private val context: Context) {

    private var modelHandle: Long = 0L
    private var ctxHandle: Long = 0L
    private val isCanceled = AtomicBoolean(false)

    var currentLoadState: ModelLoadState = ModelLoadState.Idle
        private set

    fun getRecommendedThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return if (cores > 2) cores - 1 else 1
    }

    suspend fun prepareAssetModel(assetFileName: String = "gemma-4-e2b-q6.gguf"): String? = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, assetFileName)
            if (file.exists() && file.length() > 0) {
                return@withContext file.absolutePath
            }
            context.assets.open(assetFileName).use { inputStream ->
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            if (file.exists() && file.length() > 0) file.absolutePath else null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun loadModel(
        modelPath: String,
        useVulkan: Boolean = true,
        nCtx: Int = 4096,
        nThreads: Int = getRecommendedThreads()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            currentLoadState = ModelLoadState.Loading("Loading GGUF model...")
            unload()

            val handle = LlamaNative.nativeLoadModel(
                modelPath = modelPath,
                useVulkan = useVulkan,
                nGpuLayers = if (useVulkan) 99 else 0
            )

            if (handle == 0L) {
                currentLoadState = ModelLoadState.Error("Failed to load model file.")
                return@withContext Result.failure(RuntimeException("Failed to load model file at $modelPath"))
            }

            modelHandle = handle
            ctxHandle = LlamaNative.nativeCreateContext(
                modelHandle = modelHandle,
                nCtx = nCtx,
                nThreads = nThreads
            )

            if (ctxHandle == 0L) {
                LlamaNative.nativeFreeModel(modelHandle)
                modelHandle = 0L
                currentLoadState = ModelLoadState.Error("Failed to initialize llama context.")
                return@withContext Result.failure(RuntimeException("Failed to create llama context"))
            }

            val fileName = File(modelPath).name
            val quant = if (fileName.contains("q6", ignoreCase = true)) "Q6_K" else "GGUF"
            currentLoadState = ModelLoadState.Loaded(
                modelPath = modelPath,
                modelName = fileName,
                quantization = quant,
                isVulkan = useVulkan
            )

            Result.success(Unit)
        } catch (e: Exception) {
            currentLoadState = ModelLoadState.Error(e.localizedMessage ?: "Unknown load error")
            Result.failure(e)
        }
    }

    fun stopGeneration() {
        isCanceled.set(true)
    }

    fun generateStreamWithFlow(
        prompt: String,
        maxTokens: Int = 1024,
        temp: Float = 0.7f,
        topP: Float = 0.9f,
        repeatPenalty: Float = 1.1f,
        onStats: (GenerationStats) -> Unit = {}
    ): Flow<String> = callbackFlow {
        if (ctxHandle == 0L) {
            close(IllegalStateException("Model is not loaded."))
            return@callbackFlow
        }

        isCanceled.set(false)
        val startTime = SystemClock.elapsedRealtime()
        var tokenCount = 0

        val callback = object : LlamaNative.StreamCallback {
            override fun onToken(token: String) {
                tokenCount++
                trySend(token)
            }

            override fun isCanceled(): Boolean = isCanceled.get()
        }

        LlamaNative.nativeGenerateStream(
            ctxHandle = ctxHandle,
            prompt = prompt,
            maxTokens = maxTokens,
            temp = temp,
            topP = topP,
            repeatPenalty = repeatPenalty,
            callback = callback
        )

        val totalTimeMs = SystemClock.elapsedRealtime() - startTime
        val speed = if (totalTimeMs > 0) (tokenCount * 1000f / totalTimeMs) else 0f
        onStats(GenerationStats(tokenCount, totalTimeMs, speed))

        close()
        awaitClose { stopGeneration() }
    }.flowOn(Dispatchers.IO)

    fun unload() {
        if (ctxHandle != 0L) {
            LlamaNative.nativeFreeContext(ctxHandle)
            ctxHandle = 0L
        }
        if (modelHandle != 0L) {
            LlamaNative.nativeFreeModel(modelHandle)
            modelHandle = 0L
        }
        currentLoadState = ModelLoadState.Idle
    }
}
