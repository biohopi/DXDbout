package com.turbodns.gguf.chat

import com.turbodns.gguf.chat.data.AppSettings
import com.turbodns.gguf.chat.data.ExecutionMode
import com.turbodns.gguf.chat.data.ToolCallingRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallingRouterTest {

    @Test
    fun testFormatSystemPromptWithTools() {
        val formatted = ToolCallingRouter.formatSystemPromptWithTools("Base prompt")
        assertTrue(formatted.contains("Base prompt"))
        assertTrue(formatted.contains("<search>your search query here</search>"))
    }

    @Test
    fun testDefaultAppSettings() {
        val settings = AppSettings()
        assertEquals("https://api.openai.com/v1", settings.baseUrl)
        assertEquals(ExecutionMode.LOCAL_WITH_WEB_TOOL, settings.executionMode)
        assertTrue(settings.useVulkan)
        assertEquals(4096, settings.maxContextTokens)
    }
}
