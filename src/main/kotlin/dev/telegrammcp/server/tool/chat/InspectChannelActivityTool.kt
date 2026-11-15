package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.ToolSupport
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.server.McpSyncServerExchange
import org.springframework.stereotype.Component

@Component
class InspectChannelActivityTool(
    private val telegramClient: TelegramClientService,
    private val guardrailService: GuardrailService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {
    companion object { const val TOOL_NAME = "inspect_channel_activity" }
    private val log = StructuredLogger.forClass<InspectChannelActivityTool>()
    override fun definition() = ToolSupport.definition(TOOL_NAME,
        "Paginated read-only channel metadata across main list and archive: last event date, membership and owner/admin role. No history, message text or read-status changes. Follow nextOffset until null; last event is not proof of abandonment. Requires unrestricted chat scope.",
        """{"type":"object","properties":{
          "offset":{"type":"integer","minimum":0,"maximum":20000,"description":"Inventory offset; start at 0, follow nextOffset"},
          "limit":{"type":"integer","minimum":1,"maximum":200,"description":"Chats scanned per page (default 100); channel results may be fewer"}
        },"additionalProperties":false}""", objectMapper)
    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>) = ToolSupport.execute(
        TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to inspect channel activity", auditService,
    ) {
        if (arguments.keys.any { it !in setOf("offset", "limit") }) throw InvalidToolInputException("Only offset and limit are accepted")
        fun integer(name: String, default: Int): Int {
            val raw = arguments[name] ?: return default
            return raw.toString().toIntOrNull() ?: throw InvalidToolInputException("$name must be an integer")
        }
        val offset = integer("offset", 0)
        val limit = integer("limit", 100)
        if (offset !in 0..20_000 || limit !in 1..200) throw InvalidToolInputException("offset must be 0..20000 and limit 1..200")
        guardrailService.requireUnrestrictedChatScope(TOOL_NAME)
        telegramClient.inspectChannelActivity(offset, limit)
    }
}
