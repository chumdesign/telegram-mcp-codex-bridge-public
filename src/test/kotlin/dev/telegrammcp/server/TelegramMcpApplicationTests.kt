package dev.telegrammcp.server

import dev.telegrammcp.server.tool.McpToolHandler
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.core.env.Environment
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration smoke tests for application auto-configuration, security, MCP
 * transport, and the documented tool inventory.
 *
 * TDLib is not configured in the test profile, so the account registry exposes
 * supplies a no-op `TelegramClientService`. Tools return a clear unavailable
 * error if invoked without a real Telegram client.
 */
@SpringBootTest
@ActiveProfiles("test")
class TelegramMcpApplicationTests {

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Autowired
    private lateinit var handlers: List<McpToolHandler>

    @Autowired
    private lateinit var environment: Environment

    @Test
    fun contextLoads() {
        // The injected dependencies verify that the full application boots.
    }

    @Test
    fun `raw HTTP binds to loopback by default`() {
        assertEquals("127.0.0.1", environment.getProperty("server.address"))
    }

    @Test
    fun `registers the documented tool inventory on Streamable HTTP`() {
        val names = handlers.map { it.definition().name() }

        assertEquals(138, names.size)
        assertEquals(names.toSet(), dev.telegrammcp.server.tool.ToolContractCatalog.schemas.keys,
            "Every registered tool must have an explicit output contract")
        assertTrue("get_send_operation" in names)
        assertTrue("wait_for_changes" in names)
        assertEquals(names.size, names.toSet().size)
        assertTrue("search_public_messages" in names)
        assertFalse("get_chat_promotion_policy" in names)
        assertTrue("discover_public_chats" in names)
        assertTrue("list_accounts" in names)
        assertTrue("transcribe_voice_note" in names)
        assertTrue("get_message_link" in names)
        assertTrue("configure_chat_folder" in names)
        assertTrue("ensure_unread_private_folder" in names)
        assertTrue("inspect_chat_folder_rules" in names)
        assertTrue("list_scheduled_messages" in names)
        assertTrue("schedule_message" in names)
        assertTrue("send_article" in names)
        assertTrue("edit_article" in names)
        assertTrue("edit_photo_post" in names)
        assertTrue("validate_article" in names)
        assertTrue("send_media_album" in names)
        assertTrue("get_article" in names)
        assertTrue("find_custom_emoji" in names)
        assertTrue("set_privacy_settings" in names)
        assertTrue("set_bot_commands" in names)
        assertTrue("set_group_permissions" in names)
        assertTrue("set_admin_rights" in names)
        assertTrue("vote_poll" in names)
        assertTrue("close_poll" in names)
        assertTrue("get_message_viewers" in names)
        assertTrue("set_chat_description" in names)
        assertTrue("set_slow_mode" in names)
        assertTrue("list_invite_links" in names)
        assertTrue("revoke_invite_link" in names)
        assertTrue("reorder_chat_folders" in names)
        assertTrue("get_common_chats" in names)
        assertTrue(
            applicationContext.getBeansOfType(McpStreamableServerTransportProvider::class.java).isNotEmpty(),
        )
    }

    @Test
    fun `article MCP schemas expose new content sources and optional cover`() {
        val byName = handlers.associateBy { it.definition().name() }
        val send = byName.getValue("send_article").definition().inputSchema()
        @Suppress("UNCHECKED_CAST")
        val sendProperties = send["properties"] as Map<String, Any>
        assertTrue(sendProperties.keys.containsAll(listOf("text", "rich_markdown", "rich_html", "blocks", "photo_path")))
        assertEquals(listOf("chat_id"), send["required"])
        val edit = byName.getValue("edit_article").definition().inputSchema()
        assertEquals(listOf("chat_id", "message_id"), edit["required"])
        @Suppress("UNCHECKED_CAST")
        val editProperties = edit["properties"] as Map<String, Any>
        assertTrue(editProperties.keys.containsAll(listOf("photo_path", "remove_photo", "rich_html", "blocks")))
        val photoEdit = byName.getValue("edit_photo_post").definition().inputSchema()
        assertEquals(listOf("chat_id", "message_id"), photoEdit["required"])
        assertTrue("validate_article" in byName)
    }
}
