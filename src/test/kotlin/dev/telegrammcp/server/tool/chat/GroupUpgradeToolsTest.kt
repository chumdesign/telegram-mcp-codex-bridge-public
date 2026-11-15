package dev.telegrammcp.server.tool.chat

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.ChatNotAllowedException
import dev.telegrammcp.server.model.*
import dev.telegrammcp.server.service.*
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.*
import io.modelcontextprotocol.server.McpSyncServerExchange
import org.junit.jupiter.api.Test
import kotlin.test.*

class GroupUpgradeToolsTest {
    private val client = mockk<TelegramClientService>()
    private val resolver = mockk<EntityResolverService> { every { resolve(any<Any>()) } returns -42L }
    private val guard = mockk<GuardrailService>(relaxed = true)
    private val operations = mockk<OperationGuardService>(relaxed = true)
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val exchange = mockk<McpSyncServerExchange>(relaxed = true)
    private val preview = InspectGroupUpgradeTool(client, resolver, guard, mapper, SimpleMeterRegistry())
    private val upgrade = UpgradeGroupToSupergroupTool(client, resolver, guard, operations,
        mockk<AuditService>(relaxed = true), mapper, SimpleMeterRegistry())

    @Test fun `preview is read-only and schema does not offer history changes`() {
        every { client.inspectGroupUpgrade(-42L) } returns GroupUpgradePreview(-42, "Test", ChatType.GROUP, true, true)
        assertFalse(preview.execute(exchange, mapOf("chat_id" to "-42")).isError)
        verifyOrder { guard.validateChatAccess(-42); client.inspectGroupUpgrade(-42) }
        assertFalse((preview.definition().inputSchema()["properties"] as Map<*, *>).containsKey("confirmed"))
        assertTrue((upgrade.definition().inputSchema()["properties"] as Map<*, *>).containsKey("confirmed"))
        assertFalse(OperationGuardService.annotationsFor("inspect_group_upgrade").destructiveHint() == true)
        assertTrue(OperationGuardService.annotationsFor("upgrade_group_to_supergroup").destructiveHint())
    }
    @Test fun `migration is blocked before TDLib calls when chat scope is restricted`() {
        every { guard.requireUnrestrictedChatScope(any()) } throws ChatNotAllowedException()
        assertTrue(upgrade.execute(exchange, mapOf("chat_id" to -42)).isError)
        verify { client wasNot Called }
    }
    @Test fun `history overrides and ambiguous titles are rejected before client calls`() {
        for (args in listOf(mapOf("chat_id" to -42, "history_visible" to true), mapOf("chat_id" to "Test"))) {
            assertTrue(upgrade.execute(exchange, args).isError)
        }
        verify { client wasNot Called }
    }
    @Test fun `upgrade validates source and new target`() {
        every { client.upgradeGroupToSupergroup(-42) } returns GroupUpgradeResult(-42, -1000000000099, "Test", true, false, false, true)
        assertFalse(upgrade.execute(exchange, mapOf("chat_id" to -42, "confirmed" to true)).isError)
        verifyOrder { operations.checkPermission(any(), any()); guard.requireUnrestrictedChatScope(any())
            guard.validateChatAccess(-42); client.upgradeGroupToSupergroup(-42); guard.validateChatAccess(-1000000000099) }
    }
}
