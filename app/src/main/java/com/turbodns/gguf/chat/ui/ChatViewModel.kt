package com.turbodns.gguf.chat.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.turbodns.gguf.chat.data.*
import com.turbodns.gguf.chat.engine.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val webStatus: String? = null
)

enum class MessageSender {
    USER,
    ASSISTANT,
    SYSTEM
}

data class UiState(
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val statusText: String = "Ready",
    val modelLoadState: ModelLoadState = ModelLoadState.Idle,
    val stats: GenerationStats? = null,
    val settings: AppSettings = AppSettings()
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)
    private val webClient = WebClient()
    val modelEngine = ModelEngine(application)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
    }

    fun loadModelFromPath(path: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(statusText = "Loading model...") }
            val useVulkan = _uiState.value.settings.useVulkan
            val nCtx = _uiState.value.settings.maxContextTokens

            val result = modelEngine.loadModel(path, useVulkan = useVulkan, nCtx = nCtx)
            result.onSuccess {
                settingsRepository.updateSettings(_uiState.value.settings.copy(modelPath = path))
                _uiState.update {
                    it.copy(
                        modelLoadState = modelEngine.currentLoadState,
                        statusText = "Model loaded successfully."
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        modelLoadState = modelEngine.currentLoadState,
                        statusText = "Error loading model: ${error.localizedMessage}"
                    )
                }
            }
        }
    }

    fun copyUriToLocalAndLoad(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(statusText = "Importing model file...") }
            try {
                val context = getApplication<Application>()
                val fileName = "user_model_${System.currentTimeMillis()}.gguf"
                val destFile = File(context.filesDir, fileName)

                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }

                if (destFile.exists() && destFile.length() > 0) {
                    loadModelFromPath(destFile.absolutePath)
                } else {
                    _uiState.update { it.copy(statusText = "Failed to copy GGUF file.") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(statusText = "Error opening file: ${e.localizedMessage}") }
            }
        }
    }

    fun tryLoadAssetModel() {
        viewModelScope.launch {
            _uiState.update { it.copy(statusText = "Checking asset model...") }
            val assetPath = modelEngine.prepareAssetModel("gemma-4-e2b-q6.gguf")
            if (assetPath != null) {
                loadModelFromPath(assetPath)
            } else {
                _uiState.update { it.copy(statusText = "No asset model found. Please pick a .gguf file.") }
            }
        }
    }

    fun sendMessage(promptText: String) {
        if (promptText.isBlank() || _uiState.value.isGenerating) return

        val userMsg = ChatMessage(sender = MessageSender.USER, text = promptText.trim())
        val assistantMsgId = java.util.UUID.randomUUID().toString()
        val initialAssistantMsg = ChatMessage(
            id = assistantMsgId,
            sender = MessageSender.ASSISTANT,
            text = "",
            isStreaming = true
        )

        _uiState.update { state ->
            state.copy(
                messages = state.messages + userMsg + initialAssistantMsg,
                isGenerating = true,
                statusText = "Generating response..."
            )
        }

        viewModelScope.launch {
            val mode = _uiState.value.settings.executionMode

            try {
                when (mode) {
                    ExecutionMode.LOCAL_ONLY -> {
                        streamLocalModel(promptText, assistantMsgId)
                    }
                    ExecutionMode.WEB_ONLY -> {
                        streamWebModel(promptText, assistantMsgId)
                    }
                    ExecutionMode.LOCAL_WITH_WEB_TOOL -> {
                        streamHybridModel(promptText, assistantMsgId)
                    }
                }
            } catch (e: Exception) {
                updateAssistantMessage(assistantMsgId, "\n\n[Error: ${e.localizedMessage}]", isStreaming = false)
                _uiState.update { it.copy(isGenerating = false, statusText = "Generation failed") }
            }
        }
    }

    private suspend fun streamLocalModel(prompt: String, assistantMsgId: String) {
        val fullPrompt = "<start_of_turn>user\n$prompt<end_of_turn>\n<start_of_turn>model\n"
        var accumulated = ""

        modelEngine.generateStreamWithFlow(
            prompt = fullPrompt,
            maxTokens = _uiState.value.settings.maxTokens,
            temp = _uiState.value.settings.temperature,
            topP = _uiState.value.settings.topP,
            repeatPenalty = _uiState.value.settings.repeatPenalty,
            onStats = { stats ->
                _uiState.update { it.copy(stats = stats) }
            }
        ).collect { token ->
            accumulated += token
            updateAssistantMessage(assistantMsgId, accumulated, isStreaming = true)
        }

        updateAssistantMessage(assistantMsgId, accumulated, isStreaming = false)
        _uiState.update { it.copy(isGenerating = false, statusText = "Done") }
    }

    private suspend fun streamWebModel(prompt: String, assistantMsgId: String) {
        var accumulated = ""
        webClient.streamWebCompletion(
            baseUrl = _uiState.value.settings.baseUrl,
            apiKey = _uiState.value.settings.apiKey,
            modelId = _uiState.value.settings.modelId,
            prompt = prompt
        ).collect { token ->
            accumulated += token
            updateAssistantMessage(assistantMsgId, accumulated, isStreaming = true)
        }
        updateAssistantMessage(assistantMsgId, accumulated, isStreaming = false)
        _uiState.update { it.copy(isGenerating = false, statusText = "Done") }
    }

    private suspend fun streamHybridModel(prompt: String, assistantMsgId: String) {
        var accumulated = ""
        ToolCallingRouter.executeHybridGeneration(
            userQuery = prompt,
            systemPrompt = "You are a helpful, factual AI assistant.",
            modelEngine = modelEngine,
            webClient = webClient,
            settings = _uiState.value.settings,
            onStatusMessage = { status ->
                _uiState.update { it.copy(statusText = status) }
                updateAssistantMessageStatus(assistantMsgId, status)
            },
            onStats = { stats ->
                _uiState.update { it.copy(stats = stats) }
            }
        ).collect { token ->
            accumulated += token
            updateAssistantMessage(assistantMsgId, accumulated, isStreaming = true)
        }
        updateAssistantMessage(assistantMsgId, accumulated, isStreaming = false)
        _uiState.update { it.copy(isGenerating = false, statusText = "Done") }
    }

    private fun updateAssistantMessage(id: String, text: String, isStreaming: Boolean) {
        _uiState.update { state ->
            val updatedMessages = state.messages.map { msg ->
                if (msg.id == id) msg.copy(text = text, isStreaming = isStreaming) else msg
            }
            state.copy(messages = updatedMessages)
        }
    }

    private fun updateAssistantMessageStatus(id: String, webStatus: String) {
        _uiState.update { state ->
            val updatedMessages = state.messages.map { msg ->
                if (msg.id == id) msg.copy(webStatus = webStatus) else msg
            }
            state.copy(messages = updatedMessages)
        }
    }

    fun stopGeneration() {
        modelEngine.stopGeneration()
        _uiState.update { it.copy(isGenerating = false, statusText = "Generation stopped by user") }
    }

    fun clearChat() {
        _uiState.update { it.copy(messages = emptyList(), stats = null, statusText = "Chat cleared") }
    }

    fun updateSettings(newSettings: AppSettings) {
        viewModelScope.launch {
            settingsRepository.updateSettings(newSettings)
        }
    }

    override fun onCleared() {
        super.onCleared()
        modelEngine.unload()
    }
}
