package dev.telegrammcp.server.tool.chat

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

private const val GROUP_UPGRADE_SCHEMA = """{"type":"object","properties":{
  "chat_id":{"type":["string","integer"],"description":"Exact numeric ID of the existing group; use inspect_group_upgrade first"}
},"required":["chat_id"],"additionalProperties":false}"""
private val GROUP_UPGRADE_WRITE_SCHEMA = GROUP_UPGRADE_SCHEMA.replace("\"properties\":{", """"properties":{
  "confirmed":{"type":"boolean","description":"Caller acknowledgement where required; never replaces native human approval"},""")

@Component
class InspectGroupUpgradeTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {
    companion object { const val TOOL_NAME = "inspect_group_upgrade" }
    private val log = StructuredLogger.forClass<InspectGroupUpgradeTool>()
    override fun definition(): McpSchema.Tool = ToolSupport.definition(TOOL_NAME,
        "Read-only migration preview: group type, owner rights, prior migration and current history visibility; no message reading",
        GROUP_UPGRADE_SCHEMA, objectMapper)
    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>) = ToolSupport.execute(
        toolName = TOOL_NAME, arguments = arguments, objectMapper = objectMapper,
        meterRegistry = meterRegistry, log = log, failureMessage = "Failed to inspect group migration",
    ) {
        val chatId = exactGroupId(arguments, entityResolver)
        guardrailService.validateChatAccess(chatId)
        val result = telegramClient.inspectGroupUpgrade(chatId)
        result.upgradedToChatId?.let { guardrailService.validateChatAccess(it) }
        result
    }
}

@Component
class UpgradeGroupToSupergroupTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {
    companion object { const val TOOL_NAME = "upgrade_group_to_supergroup" }
    private val log = StructuredLogger.forClass<UpgradeGroupToSupergroupTool>()
    override fun definition(): McpSchema.Tool = ToolSupport.definition(TOOL_NAME,
        "Irreversibly migrate an owned basic group to a private supergroup with a new ID. Never toggle history visibility; verify it after migration. Requires human approval. Inspect first; never retry an uncertain result automatically.",
        GROUP_UPGRADE_WRITE_SCHEMA, objectMapper)
    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>) = ToolSupport.execute(
        toolName = TOOL_NAME, arguments = arguments, objectMapper = objectMapper,
        meterRegistry = meterRegistry, log = log, failureMessage = "Failed to upgrade group",
        auditService = auditService,
    ) {
        operationGuardService.checkPermission(TOOL_NAME, arguments)
        guardrailService.requireUnrestrictedChatScope(TOOL_NAME)
        val chatId = exactGroupId(arguments, entityResolver, allowConfirmation = true)
        guardrailService.validateChatAccess(chatId)
        val result = telegramClient.upgradeGroupToSupergroup(chatId)
        guardrailService.validateChatAccess(result.chatId)
        result
    }
}

private fun exactGroupId(arguments: Map<String, Any>, resolver: EntityResolverService, allowConfirmation: Boolean = false): Long {
    if (arguments.keys.any { it != "chat_id" && !(allowConfirmation && it == "confirmed") }) throw InvalidToolInputException("Only chat_id and optional caller confirmation are accepted; history visibility cannot be changed by this tool")
    val raw = arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required")
    if (!raw.toString().matches(Regex("-[0-9]+"))) throw InvalidToolInputException("chat_id must be the exact negative numeric group ID")
    return resolver.resolve(raw)
}
