package dev.telegrammcp.server.tool.media

import dev.telegrammcp.server.tool.ToolNumbers
import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.config.ServerModeProperties
import dev.telegrammcp.server.exception.GuardrailViolationException
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.ToolSupport
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * MCP tool: **download_media**
 *
 * Downloads media from a Telegram message. The file is saved locally
 * by TDLib and the local path is returned.
 *
 * Files are saved in the account's TDLib cache; callers cannot choose a destination.
 */
@Component
class DownloadMediaTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val serverModeProperties: ServerModeProperties = ServerModeProperties(),
) : McpToolHandler {

    private val log = StructuredLogger.forClass<DownloadMediaTool>()

    companion object {
        const val TOOL_NAME = "download_media"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": {
              "type": ["string", "integer"],
              "description": "Chat identifier: numeric ID, @username, +phone, or the canonical value self"
            },
            "message_id": {
              "type": "integer",
              "description": "ID of the message containing media to download"
            }
          },
          "required": ["chat_id", "message_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool =
        ToolSupport.definition(TOOL_NAME, "Download media from a Telegram message to a local file", INPUT_SCHEMA, objectMapper)

    override fun execute(
        exchange: McpSyncServerExchange,
        arguments: Map<String, Any>,
    ): McpSchema.CallToolResult = ToolSupport.execute(
        toolName = TOOL_NAME,
        arguments = arguments,
        objectMapper = objectMapper,
        meterRegistry = meterRegistry,
        log = log,
        failureMessage = "Failed to download media",
        auditService = auditService,
    ) {
        operationGuardService.checkPermission(TOOL_NAME, arguments)

        val chatId = resolveChatId(arguments)
        val messageId = extractMessageId(arguments)

        log.withTool(TOOL_NAME).info("Downloading media from message {} in chat {}", messageId, chatId)

        guardrailService.validateChatAccess(chatId)

        val downloadScope = serverModeProperties.writeScope
        val scopedDownload = downloadScope.enabled && downloadScope.allowMediaDownload
        val root = if (scopedDownload) Path.of(downloadScope.mediaDownloadRoot).toRealPath() else null
        if (root != null) {
            if (!Files.isDirectory(root)) throw GuardrailViolationException("The configured download root is not a directory")
            val declaredSize = telegramClient.getMediaInfo(chatId, messageId).fileSize
            if (downloadScope.personalMode) {
                val available = Files.getFileStore(root).usableSpace - 256L * 1024 * 1024
                if (available <= 0 || (declaredSize ?: 0L) > available) {
                    throw GuardrailViolationException("Not enough free disk space in the Telegram download directory")
                }
            } else if (declaredSize == null || declaredSize <= 0L || declaredSize > downloadScope.maxMediaDownloadBytes) {
                throw GuardrailViolationException("Media size is unknown or exceeds the configured download limit")
            }
        }

        val result = telegramClient.downloadMedia(chatId, messageId)
        if (root != null) {
            val path = Path.of(result.localPath).toRealPath()
            if (!path.startsWith(root) || !Files.isRegularFile(path) || !Files.isReadable(path)) {
                throw GuardrailViolationException("Downloaded media is outside the safe local download scope")
            }
            val actualSize = Files.size(path)
            if (actualSize != result.fileSize || actualSize <= 0L) {
                throw GuardrailViolationException("Downloaded media is empty or incomplete")
            }
            if (!downloadScope.personalMode && actualSize > downloadScope.maxMediaDownloadBytes) {
                throw GuardrailViolationException("Downloaded media exceeds the configured download limit")
            }
        }
        objectMapper.writeValueAsString(result)
    }

    private fun resolveChatId(args: Map<String, Any>): Long {
        val raw = args["chat_id"] ?: throw InvalidToolInputException("chat_id is required")
        return entityResolver.resolve(raw)
    }

    private fun extractMessageId(args: Map<String, Any>): Long {
        val raw = args["message_id"] ?: throw InvalidToolInputException("message_id is required")
        return ToolNumbers.long(raw, "message_id")
    }
}
