package dev.telegrammcp.server.tool.media

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.service.*
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.ToolSupport
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/** Patches one existing Article, or converts an editable text post into one, in place. */
@Component
class EditArticleTool(
    private val telegramClient: TelegramClientService,
    private val resolver: EntityResolverService,
    private val guardrails: GuardrailService,
    private val files: FileSecurityService,
    private val operations: OperationGuardService,
    private val audit: AuditService,
    private val mapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {
    private val log = StructuredLogger.forClass<EditArticleTool>()

    companion object {
        const val TOOL_NAME = "edit_article"
        private val INPUT_SCHEMA = """
        {
          "type":"object",
          "properties":{
            "chat_id":{"type":["string","integer"],"description":"Allowed chat ID or self"},
            "message_id":{"type":["string","integer"],"description":"Existing editable Article or text message ID"},
            "text":{"type":"string","description":"Replacement plain text; omit all content sources to preserve current content"},
            "rich_markdown":{"type":"string","description":"Rich Markdown source"},
            "rich_html":{"type":"string","description":"Restricted inert Rich HTML source"},
            "blocks":{"type":"array","items":{"type":"object"},"description":"Structured native Article blocks"},
            "photo_path":{"type":"string","description":"Add or replace the first Article photo; omit to preserve existing photos"},
            "remove_photo":{"type":"boolean","description":"Explicitly remove Article photos; default false"}
          },
          "required":["chat_id","message_id"]
        }
        """
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME, "Edit an Article or convert a text post in place; omitted content and photos are preserved",
        INPUT_SCHEMA, mapper,
    )

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, mapper, meterRegistry, log, "Failed to edit Article", audit) {
            operations.checkPermission(TOOL_NAME, arguments)
            val chatId = resolver.resolve(arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required"))
            guardrails.validateChatAccess(chatId)
            val messageId = MediaArguments.positiveInteger(arguments["message_id"]
                ?: throw InvalidToolInputException("message_id is required"), "message_id")
            val draft = ArticleToolArguments.draft(arguments, files)
            files.withStagedUploads(listOfNotNull(draft.photoPath)) { staged ->
                telegramClient.editArticle(chatId, messageId, draft.copy(photoPath = staged.firstOrNull()?.toString()))
            }
        }
}
