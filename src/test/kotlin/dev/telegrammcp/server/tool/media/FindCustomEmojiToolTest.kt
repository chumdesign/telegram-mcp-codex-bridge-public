package dev.telegrammcp.server.tool.media

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.CustomEmojiCandidate
import dev.telegrammcp.server.service.AuditService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FindCustomEmojiToolTest {
    private val client = mockk<TelegramClientService>()
    private val audit = mockk<AuditService>(relaxed = true)
    private val exchange = mockk<McpSyncServerExchange>(relaxed = true)
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val tool = FindCustomEmojiTool(client, audit, mapper, SimpleMeterRegistry())

    @Test
    fun `returns exact candidate without reading chats or sending messages`() {
        every { client.findCustomEmoji("ageoflight_logo", "✨") } returns listOf(
            CustomEmojiCandidate(123456789L, "✨", "ageoflight_logo", "Common Symbols"),
        )

        val result = tool.execute(exchange, mapOf("set_name" to "ageoflight_logo", "emoji" to "✨"))

        assertFalse(result.isError)
        val json = mapper.readTree((result.content.first() as McpSchema.TextContent).text())
        assertEquals(123456789L, json["matches"][0]["customEmojiId"].asLong())
        verify(exactly = 0) { client.sendMessage(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { client.getHistory(any(), any(), any(), any()) }
    }

    @Test
    fun `rejects malformed set name before accessing Telegram`() {
        val result = tool.execute(exchange, mapOf("set_name" to "../other", "emoji" to "✨"))
        assertTrue(result.isError)
        verify(exactly = 0) { client.findCustomEmoji(any(), any()) }
    }

    @Test
    fun `returns empty matches without guessing a custom emoji id`() {
        every { client.findCustomEmoji("ageoflight_logo", "✨") } returns emptyList()
        val result = tool.execute(exchange, mapOf("set_name" to "ageoflight_logo", "emoji" to "✨"))
        assertFalse(result.isError)
        val json = mapper.readTree((result.content.first() as McpSchema.TextContent).text())
        assertEquals(0, json["matches"].size())
    }
}
