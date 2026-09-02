package com.turbodns.gguf.chat.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class WebClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetchWebSummary(
        baseUrl: String,
        apiKey: String,
        modelId: String,
        query: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = normalizeUrl(baseUrl, "/chat/completions")
            val systemPrompt = "You are a web search assistant. Retrieve accurate and up-to-date facts, search results, or news related to the user query. Provide a concise, highly factual summary without commentary."

            val jsonBody = JSONObject().apply {
                put("model", modelId.ifBlank { "gpt-4o-mini" })
                put("temperature", 0.2)
                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", query)
                    })
                }
                put("messages", messages)
            }

            val requestBuilder = Request.Builder()
                .url(url)
                .post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

            if (apiKey.isNotBlank()) {
                requestBuilder.header("Authorization", "Bearer $apiKey")
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        RuntimeException("HTTP ${response.code}: ${response.message}\n$bodyStr")
                    )
                }

                val jsonResponse = JSONObject(bodyStr)
                val choices = jsonResponse.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val messageObj = choices.getJSONObject(0).optJSONObject("message")
                    val content = messageObj?.optString("content") ?: ""
                    Result.success(content.trim())
                } else {
                    Result.failure(RuntimeException("No choices returned from Web API response."))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun streamWebCompletion(
        baseUrl: String,
        apiKey: String,
        modelId: String,
        prompt: String
    ): Flow<String> = flow {
        val url = normalizeUrl(baseUrl, "/chat/completions")
        val jsonBody = JSONObject().apply {
            put("model", modelId.ifBlank { "gpt-4o-mini" })
            put("stream", true)
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            }
            put("messages", messages)
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

        if (apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        val response = client.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful) {
            val errorMsg = response.body?.string() ?: "HTTP ${response.code}"
            throw RuntimeException("Web API Stream Error: $errorMsg")
        }

        val inputStream = response.body?.byteStream() ?: throw RuntimeException("Null response body")
        BufferedReader(InputStreamReader(inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line?.trim() ?: continue
                if (l.startsWith("data: ")) {
                    val data = l.removePrefix("data: ").trim()
                    if (data == "[DONE]") break
                    try {
                        val json = JSONObject(data)
                        val choices = json.optJSONArray("choices")
                        if (choices != null && choices.length() > 0) {
                            val delta = choices.getJSONObject(0).optJSONObject("delta")
                            val content = delta?.optString("content") ?: ""
                            if (content.isNotEmpty()) {
                                emit(content)
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun normalizeUrl(baseUrl: String, path: String): String {
        var cleanBase = baseUrl.trim()
        if (!cleanBase.startsWith("http://") && !cleanBase.startsWith("https://")) {
            cleanBase = "https://$cleanBase"
        }
        if (cleanBase.endsWith("/")) {
            cleanBase = cleanBase.substring(0, cleanBase.length - 1)
        }
        return if (cleanBase.endsWith("/v1")) {
            "$cleanBase$path"
        } else {
            "$cleanBase/v1$path"
        }
    }
}
