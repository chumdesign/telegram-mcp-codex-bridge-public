package dev.telegrammcp.server.tool.media

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.ToolSupport
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/** Reads every block of a native Article, including embedded photo metadata. */
@Component
class GetArticleTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {
    private val log = StructuredLogger.forClass<GetArticleTool>()

    companion object {
        const val TOOL_NAME = "get_article"
        private val INPUT_SCHEMA = """
        {
          "type":"object",
          "properties":{
            "chat_id":{"type":["string","integer"],"description":"Chat identifier"},
            "message_id":{"type":["string","integer"],"description":"Native Article message ID"}
          },
          "required":["chat_id","message_id"]
        }
        """
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Read the complete blocks and embedded photo metadata of a native Telegram Article",
        INPUT_SCHEMA,
        objectMapper,
    )

    override fun execute(
        exchange: McpSyncServerExchange,
        arguments: Map<String, Any>,
    ): McpSchema.CallToolResult = ToolSupport.execute(
        TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to read Article", auditService,
    ) {
        val rawChatId = arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required")
        val chatId = entityResolver.resolve(rawChatId)
        guardrailService.validateChatAccess(chatId)
        val messageId = MediaArguments.positiveInteger(arguments["message_id"]
            ?: throw InvalidToolInputException("message_id is required"), "message_id")
        telegramClient.getArticle(chatId, messageId)
    }
}
