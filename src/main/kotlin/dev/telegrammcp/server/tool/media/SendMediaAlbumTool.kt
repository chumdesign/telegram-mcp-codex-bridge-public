package dev.telegrammcp.server.tool.media

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.MediaContentFactory
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

@Component
class SendMediaAlbumTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val fileSecurityService: FileSecurityService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val sendOperations: SendOperationService? = null,
) : McpToolHandler {
    private val log = StructuredLogger.forClass<SendMediaAlbumTool>()
    companion object {
        const val TOOL_NAME = "send_media_album"
        private val SCHEMA = """{
          "type":"object","properties":{
            "chat_id":{"type":["string","integer"]},
            "message_thread_id":{"type":["string","integer"]},
            "reply_to_message_id":{"type":["string","integer"],"description":"Native TDLib message ID"},
            "send_at":{"type":["string","integer"],"description":"Future ISO-8601 instant or Unix seconds"},
            "files":{"type":"array","minItems":2,"maxItems":10,"items":{
              "type":"object","properties":{
                "file_path":{"type":"string"},"caption":{"type":"string"},
                "media_type":{"type":"string","enum":["auto","photo","document","video","audio"]},
                "parse_mode":{"type":"string","enum":["plain","html","markdown"]}
              },"required":["file_path"]}}
          },"required":["chat_id","files"]
        }"""
    }
    override fun definition(): McpSchema.Tool = ToolSupport.definition(TOOL_NAME,
        "Send one album of 2-10 photos/videos, documents or audio; validates all files before sending", SCHEMA, objectMapper)

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to send album", auditService) {
            dev.telegrammcp.server.tool.RecoverableSend.check(operationGuardService, TOOL_NAME, arguments)
            val chatId = entityResolver.resolve(arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required"))
            guardrailService.validateChatAccess(chatId)
            val options = MediaArguments.options(arguments)
            val files = arguments["files"] as? List<*> ?: throw InvalidToolInputException("files must be an array")
            if (files.size !in 2..10) throw InvalidToolInputException("An album needs 2 to 10 files")
            val uploads = files.map { item ->
                val raw = item as? Map<*, *> ?: throw InvalidToolInputException("Each file must be an object")
                if (raw.keys.any { it !in setOf("file_path", "caption", "media_type", "parse_mode") }) {
                    throw InvalidToolInputException("Unknown album item field")
                }
                @Suppress("UNCHECKED_CAST")
                MediaArguments.upload(raw as Map<String, Any>, fileSecurityService, guardrailService)
            }
            MediaContentFactory.validateAlbum(uploads)
            fileSecurityService.withStagedUploads(uploads.map { it.filePath }) { staged ->
                val stagedUploads = uploads.zip(staged) { item, path -> item.copy(filePath = path.toString()) }
                dev.telegrammcp.server.tool.RecoverableSend.execute(sendOperations, operationGuardService, TOOL_NAME,
                    arguments, chatId, listOf(uploads, options, staged.map {
                        dev.telegrammcp.server.tool.RecoverableSend.fileFingerprint(arguments, it)
                    }), expectedCount = uploads.size, scheduled = options.sendAtEpochSeconds != null) {
                    telegramClient.sendMediaAlbum(chatId, stagedUploads, options)
                }
            }
        }
}
