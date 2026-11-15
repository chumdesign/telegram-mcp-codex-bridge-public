package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.ChatFolderDefinition
import dev.telegrammcp.server.model.ChatFolderDetails
import dev.telegrammcp.server.model.ChatFolderInfo
import dev.telegrammcp.server.model.ChatFolderListing
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

class InspectChatFolderRulesToolTest {
    private val client = mockk<TelegramClientService>()
    private val audit = mockk<AuditService>(relaxed = true)
    private val exchange = mockk<McpSyncServerExchange>(relaxed = true)
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val tool = InspectChatFolderRulesTool(client, audit, mapper, SimpleMeterRegistry())

    @Test
    fun `returns rule metadata and counts without member IDs`() {
        every { client.listChatFolders() } returns ChatFolderListing(
            listOf(ChatFolderInfo(7, "Work")), initialized = true,
        )
        every { client.getChatFolder(7) } returns ChatFolderDetails(
            7, ChatFolderDefinition(title = "Work", includedChatIds = listOf(123456789L), includeGroups = true),
        )
        every { client.getChatFolderChosenChatCountMax() } returns 200L

        val result = tool.execute(exchange, emptyMap())

        assertFalse(result.isError)
        val json = mapper.readTree((result.content.first() as McpSchema.TextContent).text())
        assertEquals(1, json["folderCount"].asInt())
        assertEquals(200, json["maxChosenChatsPerFolder"].asLong())
        assertEquals(1, json["folders"][0]["alwaysIncludedCount"].asInt())
        assertTrue(json["folders"][0]["includeGroups"].asBoolean())
        assertFalse(json.toString().contains("123456789"))
        verify(exactly = 0) { client.getChats(any()) }
        verify(exactly = 0) { client.getHistory(any(), any(), any(), any()) }
    }

    @Test
    fun `rejects caller arguments and never fetches folder members`() {
        val result = tool.execute(exchange, mapOf("folder_id" to 7))
        assertTrue(result.isError)
        verify(exactly = 0) { client.listChatFolders() }
    }
}
