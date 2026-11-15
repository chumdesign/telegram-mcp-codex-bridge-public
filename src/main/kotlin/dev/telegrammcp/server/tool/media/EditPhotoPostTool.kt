package dev.telegrammcp.server.tool.media

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.service.*
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.ToolInputParsers
import dev.telegrammcp.server.tool.ToolSupport
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/** Edits an ordinary photo post in place, preserving omitted caption and photo fields. */
@Component
class EditPhotoPostTool(
    private val telegramClient: TelegramClientService,
    private val resolver: EntityResolverService,
    private val guardrails: GuardrailService,
    private val files: FileSecurityService,
    private val operations: OperationGuardService,
    private val audit: AuditService,
    private val mapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {
    private val log = StructuredLogger.forClass<EditPhotoPostTool>()

    companion object {
        const val TOOL_NAME = "edit_photo_post"
        private val SCHEMA = """
        {
          "type":"object",
          "properties":{
            "chat_id":{"type":["string","integer"],"description":"Chat ID or self"},
            "message_id":{"type":["string","integer"],"description":"Existing ordinary photo-post message ID"},
            "photo_path":{"type":"string","description":"Replacement local JPG/PNG; omit to keep existing photo"},
            "caption":{"type":"string","description":"Replacement caption; omit to keep existing caption"},
            "parse_mode":{"type":"string","enum":["plain","html","markdown"],"description":"Caption formatting, default plain"}
          },
          "required":["chat_id","message_id"]
        }
        """
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME, "Replace a photo or edit its caption in place without losing omitted fields", SCHEMA, mapper,
    )

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, mapper, meterRegistry, log, "Failed to edit photo post", audit) {
            operations.checkPermission(TOOL_NAME, arguments)
            val chatId = resolver.resolve(arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required"))
            guardrails.validateChatAccess(chatId)
            val messageId = MediaArguments.positiveInteger(arguments["message_id"]
                ?: throw InvalidToolInputException("message_id is required"), "message_id")
            val photoPath = arguments["photo_path"]?.let { raw ->
                val path = raw as? String ?: throw InvalidToolInputException("photo_path must be a string")
                files.validateForUpload(path, setOf(".jpg", ".jpeg", ".png"), 10L * 1024 * 1024).toString()
            }
            val caption = arguments["caption"]?.let {
                it as? String ?: throw InvalidToolInputException("caption must be a string")
            }
            caption?.let(guardrails::validateInput)
            files.withStagedUploads(listOfNotNull(photoPath)) { staged ->
                telegramClient.editPhotoPost(
                    chatId, messageId, staged.firstOrNull()?.toString(), caption, ToolInputParsers.parseMode(arguments),
                )
            }
        }
}
