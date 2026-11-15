package dev.telegrammcp.server.tool.media

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.FileSecurityService
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.ToolSupport
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/** Local preview and live TDLib limit check. Never sends a Telegram message. */
@Component
class ValidateArticleTool(
    private val telegramClient: TelegramClientService,
    private val files: FileSecurityService,
    private val audit: AuditService,
    private val mapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {
    private val log = StructuredLogger.forClass<ValidateArticleTool>()

    companion object {
        const val TOOL_NAME = "validate_article"
        private val INPUT_SCHEMA = """
        {
          "type":"object",
          "properties":{
            "text":{"type":"string","description":"Legacy plain text; exactly one content source required"},
            "rich_markdown":{"type":"string","description":"Rich Markdown source"},
            "rich_html":{"type":"string","description":"Restricted inert Rich HTML source"},
            "blocks":{"type":"array","items":{"type":"object"},"description":"Structured native Article blocks"},
            "photo_path":{"type":"string","description":"Optional local JPG/PNG cover"}
          }
        }
        """
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME, "Validate a native Article without publishing; return actual TDLib limits and measured counts",
        INPUT_SCHEMA, mapper,
    )

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, mapper, meterRegistry, log, "Article validation failed", audit) {
            telegramClient.validateArticle(ArticleToolArguments.draft(arguments, files))
        }
}
