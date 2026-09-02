package com.turbodns.gguf.chat.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ExecutionMode {
    LOCAL_ONLY,
    WEB_ONLY,
    LOCAL_WITH_WEB_TOOL
}

data class AppSettings(
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val modelId: String = "gpt-4o-mini",
    val executionMode: ExecutionMode = ExecutionMode.LOCAL_WITH_WEB_TOOL,
    val useVulkan: Boolean = true,
    val maxContextTokens: Int = 4096,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val repeatPenalty: Float = 1.1f,
    val maxTokens: Int = 1024,
    val modelPath: String = ""
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val API_KEY = stringPreferencesKey("api_key")
        val MODEL_ID = stringPreferencesKey("model_id")
        val EXECUTION_MODE = stringPreferencesKey("execution_mode")
        val USE_VULKAN = booleanPreferencesKey("use_vulkan")
        val MAX_CONTEXT_TOKENS = intPreferencesKey("max_context_tokens")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val TOP_P = floatPreferencesKey("top_p")
        val REPEAT_PENALTY = floatPreferencesKey("repeat_penalty")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val MODEL_PATH = stringPreferencesKey("model_path")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            baseUrl = prefs[Keys.BASE_URL] ?: "https://api.openai.com/v1",
            apiKey = prefs[Keys.API_KEY] ?: "",
            modelId = prefs[Keys.MODEL_ID] ?: "gpt-4o-mini",
            executionMode = try {
                ExecutionMode.valueOf(prefs[Keys.EXECUTION_MODE] ?: ExecutionMode.LOCAL_WITH_WEB_TOOL.name)
            } catch (e: Exception) {
                ExecutionMode.LOCAL_WITH_WEB_TOOL
            },
            useVulkan = prefs[Keys.USE_VULKAN] ?: true,
            maxContextTokens = prefs[Keys.MAX_CONTEXT_TOKENS] ?: 4096,
            temperature = prefs[Keys.TEMPERATURE] ?: 0.7f,
            topP = prefs[Keys.TOP_P] ?: 0.9f,
            repeatPenalty = prefs[Keys.REPEAT_PENALTY] ?: 1.1f,
            maxTokens = prefs[Keys.MAX_TOKENS] ?: 1024,
            modelPath = prefs[Keys.MODEL_PATH] ?: ""
        )
    }

    suspend fun updateSettings(settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BASE_URL] = settings.baseUrl
            prefs[Keys.API_KEY] = settings.apiKey
            prefs[Keys.MODEL_ID] = settings.modelId
            prefs[Keys.EXECUTION_MODE] = settings.executionMode.name
            prefs[Keys.USE_VULKAN] = settings.useVulkan
            prefs[Keys.MAX_CONTEXT_TOKENS] = settings.maxContextTokens
            prefs[Keys.TEMPERATURE] = settings.temperature
            prefs[Keys.TOP_P] = settings.topP
            prefs[Keys.REPEAT_PENALTY] = settings.repeatPenalty
            prefs[Keys.MAX_TOKENS] = settings.maxTokens
            prefs[Keys.MODEL_PATH] = settings.modelPath
        }
    }
}
