package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.ChatFolderDefinition
import dev.telegrammcp.server.model.ChatFolderDetails
import dev.telegrammcp.server.model.ChatFolderInfo
import dev.telegrammcp.server.model.ChatFolderListing
import dev.telegrammcp.server.model.JoinNoticeAudit
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.OperationGuardService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.server.McpSyncServerExchange
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnsureUnreadPrivateFolderToolTest {
    private val client = mockk<TelegramClientService>()
    private val guard = mockk<OperationGuardService>(relaxed = true)
    private val audit = mockk<AuditService>(relaxed = true)
    private val exchange = mockk<McpSyncServerExchange>(relaxed = true)
    private val tool = EnsureUnreadPrivateFolderTool(
        client, guard, audit, jacksonObjectMapper().findAndRegisterModules(), SimpleMeterRegistry(),
    )
    private val target = ChatFolderDefinition(
        title = EnsureUnreadPrivateFolderTool.FOLDER_TITLE,
        excludeRead = true,
        includeContacts = true,
        includeNonContacts = true,
    )

    @Test
    fun `creates only a dynamic unread private folder without reading chats or messages`() {
        every { client.listChatFolders() } returns ChatFolderListing(emptyList(), initialized = true)
        every { client.createChatFolder(target) } returns ChatFolderInfo(7, target.title)
        every { client.getChatFolderChatCount(target) } returns 42
        every { client.getExactChatFolderChatCount(7) } returns 42
        every { client.getChatFolderChosenChatCountMax() } returns 100L
        every { client.inspectChatFolderJoinNotices(7) } returns JoinNoticeAudit(42, 7, 6)

        val result = tool.execute(exchange, emptyMap())

        assertFalse(result.isError)
        verify { guard.checkPermission(EnsureUnreadPrivateFolderTool.TOOL_NAME, emptyMap()) }
        verify(exactly = 1) { client.createChatFolder(target) }
        verify(exactly = 0) { client.getChats(any()) }
        verify(exactly = 1) { client.getChatFolderChatCount(target) }
        verify(exactly = 1) { client.getExactChatFolderChatCount(7) }
        verify(exactly = 1) { client.getChatFolderChosenChatCountMax() }
        verify(exactly = 1) { client.inspectChatFolderJoinNotices(7) }
    }

    @Test
    fun `reuses an existing matching folder without changing it`() {
        every { client.listChatFolders() } returns ChatFolderListing(
            listOf(ChatFolderInfo(7, target.title)), initialized = true,
        )
        every { client.getChatFolder(7) } returns ChatFolderDetails(7, target.copy(iconName = "Private"))
        every { client.getChatFolderChatCount(target) } returns 42
        every { client.getExactChatFolderChatCount(7) } returns 42
        every { client.getChatFolderChosenChatCountMax() } returns 100L
        every { client.inspectChatFolderJoinNotices(7) } returns JoinNoticeAudit(42, 7, 6)

        val result = tool.execute(exchange, emptyMap())

        assertFalse(result.isError)
        verify(exactly = 0) { client.createChatFolder(any()) }
        verify(exactly = 0) { client.updateChatFolder(any(), any()) }
        verify(exactly = 1) { client.getChatFolderChatCount(target) }
        verify(exactly = 1) { client.getExactChatFolderChatCount(7) }
        verify(exactly = 1) { client.getChatFolderChosenChatCountMax() }
        verify(exactly = 1) { client.inspectChatFolderJoinNotices(7) }
    }

    @Test
    fun `does not overwrite a differently configured folder with the same title`() {
        every { client.listChatFolders() } returns ChatFolderListing(
            listOf(ChatFolderInfo(7, target.title)), initialized = true,
        )
        every { client.getChatFolder(7) } returns ChatFolderDetails(7, target.copy(includeGroups = true))

        val result = tool.execute(exchange, emptyMap())

        assertTrue(result.isError)
        verify(exactly = 0) { client.createChatFolder(any()) }
        verify(exactly = 0) { client.updateChatFolder(any(), any()) }
    }

    @Test
    fun `refuses to create while the folder list is not initialized`() {
        every { client.listChatFolders() } returns ChatFolderListing(emptyList(), initialized = false)

        val result = tool.execute(exchange, emptyMap())

        assertTrue(result.isError)
        verify(exactly = 0) { client.createChatFolder(any()) }
    }
}
