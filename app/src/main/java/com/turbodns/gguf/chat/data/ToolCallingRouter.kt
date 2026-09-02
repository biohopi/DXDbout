package com.turbodns.gguf.chat.data

import com.turbodns.gguf.chat.engine.GenerationStats
import com.turbodns.gguf.chat.engine.ModelEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

object ToolCallingRouter {

    private val SEARCH_TOOL_REGEX = Regex("""<search>(.*?)</search>""", RegexOption.DOT_MATCHES_ALL)

    fun formatSystemPromptWithTools(
        systemPrompt: String = "You are a helpful, accurate AI assistant."
    ): String {
        return """$systemPrompt
You have access to a web search tool. If you need current facts, real-time information, news, weather, or specific recent data, output your search query in this exact format:
<search>your search query here</search>

Otherwise, directly answer the user query."""
    }

    suspend fun executeHybridGeneration(
        userQuery: String,
        systemPrompt: String,
        modelEngine: ModelEngine,
        webClient: WebClient,
        settings: AppSettings,
        onStatusMessage: (String) -> Unit = {},
        onStats: (GenerationStats) -> Unit = {}
    ): Flow<String> = flow {
        val needsWeb = isQueryLikelyWebDependent(userQuery)

        var webContext = ""
        if (needsWeb) {
            onStatusMessage("Fetching live web context from OpenAI API...")
            val webResult = webClient.fetchWebSummary(
                baseUrl = settings.baseUrl,
                apiKey = settings.apiKey,
                modelId = settings.modelId,
                query = userQuery
            )
            webResult.onSuccess { summary ->
                webContext = summary
                onStatusMessage("Web context retrieved. Generating answer...")
            }.onFailure { err ->
                onStatusMessage("Web fetch failed (${err.localizedMessage}). Proceeding with local model...")
            }
        }

        val fullPrompt = StringBuilder()
        fullPrompt.append("<start_of_turn>system\n")
        fullPrompt.append(systemPrompt)
        if (webContext.isNotBlank()) {
            fullPrompt.append("\n\n[Web Context Information]:\n").append(webContext)
        }
        fullPrompt.append("<end_of_turn>\n")
        fullPrompt.append("<start_of_turn>user\n").append(userQuery).append("<end_of_turn>\n")
        fullPrompt.append("<start_of_turn>model\n")

        modelEngine.generateStreamWithFlow(
            prompt = fullPrompt.toString(),
            maxTokens = settings.maxTokens,
            temp = settings.temperature,
            topP = settings.topP,
            repeatPenalty = settings.repeatPenalty,
            onStats = onStats
        ).collect { token ->
            emit(token)
        }
    }

    private fun isQueryLikelyWebDependent(query: String): Boolean {
        val lower = query.lowercase()
        val webKeywords = listOf(
            "today", "yesterday", "tomorrow", "news", "weather", "stock", "price",
            "score", "latest", "recent", "who won", "current", "2024", "2025", "2026",
            "search", "find", "url", "http", "api", "release date"
        )
        return webKeywords.any { lower.contains(it) }
    }
}
