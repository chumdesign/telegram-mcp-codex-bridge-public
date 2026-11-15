package dev.telegrammcp.server.tool.media

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.FileSecurityService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.ToolSupport
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component
import java.time.Instant

/** Publishes one native Telegram Article through the connected user account. */
@Component
class SendArticleTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val fileSecurityService: FileSecurityService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val sendOperations: dev.telegrammcp.server.service.SendOperationService? = null,
) : McpToolHandler {
    private val log = StructuredLogger.forClass<SendArticleTool>()

    companion object {
        const val TOOL_NAME = "send_article"
        private val INPUT_SCHEMA = """
        {
          "type":"object",
          "properties":{
            "chat_id":{"type":["string","integer"],"description":"Allowed chat ID or self for Saved Messages"},
            "text":{"type":"string","description":"Legacy verbatim paragraphs; mutually exclusive with rich_markdown, rich_html, blocks"},
            "rich_markdown":{"type":"string","description":"Strict Rich Markdown subset: headings, bold, italic, links, lists, quotes, rules, GFM tables"},
            "rich_html":{"type":"string","description":"Inert allowlisted Rich HTML: headings, paragraphs, b/i, links, lists, blockquotes, hr, table/tr/th/td"},
            "blocks":{"type":"array","items":{"type":"object"},"description":"Native blocks: paragraph, heading, list, quote, divider, table with rows of cells and rich inline text"},
            "photo_path":{"type":"string","description":"Optional local JPG/PNG cover inside an allowed upload root"},
            "send_at":{"type":["string","integer"],"description":"Optional future ISO-8601 instant or Unix epoch seconds; omit to publish now"}
          },
          "required":["chat_id"]
        }
        """
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Publish or schedule a native Telegram Article; exactly one content source, optional cover",
        INPUT_SCHEMA,
        objectMapper,
    )

    override fun execute(
        exchange: McpSyncServerExchange,
        arguments: Map<String, Any>,
    ): McpSchema.CallToolResult = ToolSupport.execute(
        TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to send article", auditService,
    ) {
        dev.telegrammcp.server.tool.RecoverableSend.check(operationGuardService, TOOL_NAME, arguments)

        val rawChatId = arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required")
        val chatId = entityResolver.resolve(rawChatId)
        guardrailService.validateChatAccess(chatId)

        val draft = ArticleToolArguments.draft(arguments, fileSecurityService)
        val validation = telegramClient.validateArticle(draft)
        val sendAt = arguments["send_at"]?.let(::futureEpochSeconds)

        log.withTool(TOOL_NAME).info(
            "Sending native Article to chat {} (length={}, scheduled={})",
            chatId, validation.textLength, sendAt != null,
        )
        val paths = listOfNotNull(draft.photoPath)
        fileSecurityService.withStagedUploads(paths) { staged ->
            val stagedDraft = draft.copy(photoPath = staged.firstOrNull()?.toString())
            dev.telegrammcp.server.tool.RecoverableSend.execute(sendOperations, operationGuardService, TOOL_NAME,
                arguments, chatId, listOf(draft, sendAt, staged.map {
                    dev.telegrammcp.server.tool.RecoverableSend.fileFingerprint(arguments, it)
                }), scheduled = sendAt != null) {
                telegramClient.sendArticle(chatId, stagedDraft, sendAt)
            }
        }
    }

    private fun futureEpochSeconds(raw: Any): Int {
        val seconds = when (raw) {
            is Number -> raw.toLong().takeIf { it.toDouble() == raw.toDouble() }
            is String -> raw.trim().toLongOrNull() ?: runCatching { Instant.parse(raw.trim()).epochSecond }.getOrNull()
            else -> null
        } ?: throw InvalidToolInputException("send_at must be an ISO-8601 instant or Unix epoch seconds")
        if (seconds !in 1..Int.MAX_VALUE.toLong() || seconds <= Instant.now().epochSecond) {
            throw InvalidToolInputException("send_at must be a future instant within TDLib's supported epoch range")
        }
        return seconds.toInt()
    }
}
