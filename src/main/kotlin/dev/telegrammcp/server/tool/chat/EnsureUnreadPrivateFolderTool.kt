package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.ChatFolderDefinition
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.OperationGuardService
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.ToolSupport
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/** A deliberately narrow, idempotent folder mutation for the scoped Codex client. */
@Component
class EnsureUnreadPrivateFolderTool(
    private val telegramClient: TelegramClientService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {
    companion object {
        const val TOOL_NAME = "ensure_unread_private_folder"
        const val FOLDER_TITLE = "Личные 🔔"
        private val TARGET = ChatFolderDefinition(
            title = FOLDER_TITLE,
            isShareable = false,
            excludeRead = true,
            includeContacts = true,
            includeNonContacts = true,
        )
    }

    private val log = StructuredLogger.forClass<EnsureUnreadPrivateFolderTool>()

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Create or find the private unread-chats folder and count joined-Telegram notices by last-event type; never reads history or changes read status",
        """{"type":"object","properties":{},"additionalProperties":false}""",
        objectMapper,
    )

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to ensure unread private folder", auditService) {
            operationGuardService.checkPermission(TOOL_NAME, arguments)
            if (arguments.isNotEmpty()) throw InvalidToolInputException("This tool accepts no arguments")

            val listing = telegramClient.listChatFolders()
            if (!listing.initialized) throw InvalidToolInputException("Telegram folder inventory is not ready")
            val sameTitle = listing.folders.filter { it.title == FOLDER_TITLE }
            if (sameTitle.size > 1) throw InvalidToolInputException("More than one folder already has this title")
            val existing = sameTitle.singleOrNull()
            val folder = if (existing != null) {
                val definition = telegramClient.getChatFolder(existing.id).definition
                // TDLib may fill in a default icon/color even when omitted.
                if (definition.copy(iconName = null, colorId = 0) != TARGET) {
                    throw InvalidToolInputException("A folder with this title already exists with different rules; it was not changed")
                }
                existing
            } else {
                telegramClient.createChatFolder(TARGET)
            }
            val approximateChatCount = telegramClient.getChatFolderChatCount(TARGET)
            val exactChatCount = telegramClient.getExactChatFolderChatCount(folder.id)
            val maxChosenChatsPerFolder = telegramClient.getChatFolderChosenChatCountMax()
            val noticeAudit = telegramClient.inspectChatFolderJoinNotices(folder.id)
            mapOf(
                "folderId" to folder.id,
                "title" to folder.title,
                "created" to (existing == null),
                "approximateChatCount" to approximateChatCount,
                "exactChatCount" to exactChatCount,
                "maxChosenChatsPerFolder" to maxChosenChatsPerFolder,
                "noticeAuditScannedChatCount" to noticeAudit.scannedChatCount,
                "registrationLastEventCount" to noticeAudit.registrationLastEventCount,
                "registrationSoleUnreadCount" to noticeAudit.registrationSoleUnreadCount,
            )
        }
}
