package dev.telegrammcp.server.tool.media

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

/** Read-only lookup of exact custom emoji IDs from a named Telegram set. */
@Component
class FindCustomEmojiTool(
    private val telegramClient: TelegramClientService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {
    companion object {
        const val TOOL_NAME = "find_custom_emoji"
        private val SET_NAME = Regex("[A-Za-z0-9_]{1,64}")
    }

    private val log = StructuredLogger.forClass<FindCustomEmojiTool>()

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Find exact custom emoji IDs in a named Telegram emoji set and verify TDLib HTML parsing; does not read chats or send messages",
        """{"type":"object","properties":{"set_name":{"type":"string","description":"Short name from t.me/addemoji/<set_name>"},"emoji":{"type":"string","description":"Exact Unicode emoji to match, for example ✨"}},"required":["set_name","emoji"],"additionalProperties":false}""",
        objectMapper,
    )

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to find custom emoji", auditService) {
            val setName = arguments["set_name"] as? String
                ?: throw InvalidToolInputException("set_name is required")
            val emoji = arguments["emoji"] as? String
                ?: throw InvalidToolInputException("emoji is required")
            if (arguments.keys != setOf("set_name", "emoji")) throw InvalidToolInputException("Unexpected arguments")
            if (!SET_NAME.matches(setName)) throw InvalidToolInputException("Invalid set_name")
            if (emoji.isBlank() || emoji.length > 16) throw InvalidToolInputException("Invalid emoji")
            val matches = telegramClient.findCustomEmoji(setName, emoji)
            mapOf("setName" to setName, "emoji" to emoji, "matches" to matches)
        }
}
