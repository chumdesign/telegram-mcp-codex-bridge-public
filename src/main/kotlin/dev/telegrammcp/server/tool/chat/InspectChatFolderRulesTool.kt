package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.ToolSupport
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/** Read-only account folder-rule inventory. Never returns member chat IDs or message data. */
@Component
class InspectChatFolderRulesTool(
    private val telegramClient: TelegramClientService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {
    companion object {
        const val TOOL_NAME = "inspect_chat_folder_rules"
        private const val MAX_FOLDERS = 50
    }

    private val log = StructuredLogger.forClass<InspectChatFolderRulesTool>()

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Inspect own Telegram folder names, rule flags and explicit-chat counts; no member IDs or messages",
        """{"type":"object","properties":{},"additionalProperties":false}""",
        objectMapper,
    )

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to inspect chat folder rules", auditService) {
            if (arguments.isNotEmpty()) throw InvalidToolInputException("This tool accepts no arguments")
            val listing = telegramClient.listChatFolders()
            if (!listing.initialized) throw InvalidToolInputException("Telegram folder inventory is not ready")
            if (listing.folders.size > MAX_FOLDERS) throw InvalidToolInputException("Folder inventory exceeds the safe limit")
            val folders = listing.folders.map { folder ->
                val definition = telegramClient.getChatFolder(folder.id).definition
                mapOf(
                    "folderId" to folder.id,
                    "title" to folder.title,
                    "isShareable" to folder.isShareable,
                    "pinnedCount" to definition.pinnedChatIds.size,
                    "alwaysIncludedCount" to definition.includedChatIds.size,
                    "alwaysExcludedCount" to definition.excludedChatIds.size,
                    "excludeMuted" to definition.excludeMuted,
                    "excludeRead" to definition.excludeRead,
                    "excludeArchived" to definition.excludeArchived,
                    "includeContacts" to definition.includeContacts,
                    "includeNonContacts" to definition.includeNonContacts,
                    "includeBots" to definition.includeBots,
                    "includeGroups" to definition.includeGroups,
                    "includeChannels" to definition.includeChannels,
                )
            }
            mapOf(
                "folderCount" to folders.size,
                "maxChosenChatsPerFolder" to telegramClient.getChatFolderChosenChatCountMax(),
                "folders" to folders,
            )
        }
}
