package dev.telegrammcp.server.service

import dev.telegrammcp.server.config.AntiSpamProperties
import dev.telegrammcp.server.config.ServerModeProperties
import dev.telegrammcp.server.exception.ConfirmationRequiredException
import dev.telegrammcp.server.exception.GuardrailViolationException
import dev.telegrammcp.server.exception.ReadOnlyModeException
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperationGuardServiceTest {

    private val channelA = -1001111111111L
    private val channelB = -1002222222222L
    private val forumGroup = -1003333333333L

    private fun scopedPublisher(): OperationGuardService = guard(
        ServerModeProperties(
            readOnly = false,
            writeScope = ServerModeProperties.WriteScopeProps(
                enabled = true,
                allowedChatIds = listOf(channelA, channelB),
            ),
        ),
    )

    private fun guard(props: ServerModeProperties): OperationGuardService {
        // Anti-spam disabled in tests so these assertions stay focused on
        // read-only / confirmation logic only.
        val antiSpamProps = AntiSpamProperties(enabled = false, overridesFile = null)
        val antiSpam = AntiSpamGuardService(
            antiSpamProps,
            SimpleMeterRegistry(),
            AntiSpamPolicyService(antiSpamProps),
        )
        return OperationGuardService(props, antiSpam)
    }

    // ── Read-only mode ──────────────────────────────────────────────────────

    @Test
    fun `properties built without application yml are read-only`() {
        assertThrows<ReadOnlyModeException> {
            guard(ServerModeProperties()).checkPermission("send_message", emptyMap())
        }
    }

    @Test
    fun `read-only mode blocks write tools`() {
        val service = guard(ServerModeProperties(readOnly = true))

        assertThrows<ReadOnlyModeException> {
            service.checkPermission("send_message", emptyMap())
        }
    }

    @Test
    fun `read-only mode allows read tools`() {
        val service = guard(ServerModeProperties(readOnly = true))

        assertDoesNotThrow {
            service.checkPermission("get_history", emptyMap())
        }
    }

    @Test
    fun `normal mode allows write tools`() {
        val service = guard(ServerModeProperties(readOnly = false))

        assertDoesNotThrow {
            service.checkPermission("send_message", emptyMap())
        }
    }

    @Test
    fun `channel scope permits requested publishing mutations in either allowed channel`() {
        val service = scopedPublisher()
        for (tool in listOf(
            "send_message", "reply_to_message", "send_file", "send_article", "edit_article", "schedule_message", "edit_message",
            "reschedule_message", "cancel_scheduled_message",
        )) {
            assertDoesNotThrow { service.checkPermission(tool, mapOf("chat_id" to channelA)) }
            assertDoesNotThrow { service.checkPermission(tool, mapOf("chat_id" to channelB.toString())) }
        }
    }

    @Test
    fun `channel scope rejects saved messages group aliases and missing targets`() {
        val service = scopedPublisher()
        for (target in listOf<Any?>("self", 123456789L, -1003333333333L, "@example_channel", null)) {
            val arguments = target?.let { mapOf("chat_id" to it) } ?: emptyMap()
            assertThrows<GuardrailViolationException> {
                service.checkPermission("send_message", arguments)
            }
        }
    }

    @Test
    fun `forum scope permits text send and schedule in any positive topic only`() {
        val service = guard(ServerModeProperties(
            readOnly = false,
            writeScope = ServerModeProperties.WriteScopeProps(
                enabled = true, allowedChatIds = listOf(channelA, channelB),
                allowedForumChatIds = listOf(forumGroup),
            ),
        ))
        for (topicId in listOf(3L, 991L, 123456L)) {
            for (tool in listOf("send_message", "reply_to_message", "schedule_message")) {
                assertDoesNotThrow {
                    service.checkPermission(tool, mapOf("chat_id" to forumGroup, "message_thread_id" to topicId))
                }
            }
        }
        for (badTopic in listOf<Any?>(null, 0, -1, 3.5, "invalid", Int.MAX_VALUE.toLong() + 1)) {
            val arguments = mutableMapOf<String, Any>("chat_id" to forumGroup)
            if (badTopic != null) arguments["message_thread_id"] = badTopic
            assertThrows<GuardrailViolationException> { service.checkPermission("send_message", arguments) }
            assertThrows<GuardrailViolationException> { service.checkPermission("schedule_message", arguments) }
            assertThrows<GuardrailViolationException> { service.checkPermission("reply_to_message", arguments) }
        }
        for (tool in listOf("send_file", "send_article", "edit_article", "edit_message", "reschedule_message", "cancel_scheduled_message")) {
            assertThrows<GuardrailViolationException> {
                service.checkPermission(tool, mapOf("chat_id" to forumGroup, "message_thread_id" to 3L))
            }
        }
        assertThrows<GuardrailViolationException> {
            service.checkPermission("send_message", mapOf("chat_id" to forumGroup, "message_thread_id" to 3L, "reply_markup" to emptyMap<String, Any>()))
        }
        assertThrows<GuardrailViolationException> {
            service.checkPermission("send_message", mapOf("chat_id" to -1004444444444L, "message_thread_id" to 3L))
        }
    }

    @Test
    fun `saved messages rule permits only text sent to canonical self`() {
        val service = guard(ServerModeProperties(
            readOnly = false,
            writeScope = ServerModeProperties.WriteScopeProps(
                enabled = true,
                allowedChatIds = listOf(channelA, channelB),
                allowSavedMessagesText = true,
            ),
        ))
        assertDoesNotThrow {
            service.checkPermission("send_message", mapOf(
                "chat_id" to "self", "text" to "Test", "parse_mode" to "html",
            ))
        }
        assertDoesNotThrow {
            service.checkPermission("reply_to_message", mapOf(
                "chat_id" to "self", "message_id" to 42L, "text" to "Test", "parse_mode" to "html",
            ))
        }
        for (tool in listOf("send_file", "send_article", "edit_article", "schedule_message", "edit_message", "delete_message")) {
            assertThrows<GuardrailViolationException> {
                service.checkPermission(tool, mapOf("chat_id" to "self", "text" to "Test"))
            }
        }
        for (arguments in listOf(
            mapOf("chat_id" to 123456789L, "text" to "Test"),
            mapOf("chat_id" to "self", "text" to "Test", "message_thread_id" to 1L),
            mapOf("chat_id" to "self", "text" to "Test", "reply_markup" to emptyMap<String, Any>()),
        )) {
            assertThrows<GuardrailViolationException> {
                service.checkPermission("send_message", arguments)
            }
            assertThrows<GuardrailViolationException> {
                service.checkPermission("reply_to_message", arguments + ("message_id" to 42L))
            }
        }
    }

    @Test
    fun `replies do not expand chat or operation authority`() {
        val service = scopedPublisher()
        for (target in listOf<Any>("self", "@example_channel", 123456789L, forumGroup)) {
            assertThrows<GuardrailViolationException> {
                service.checkPermission("reply_to_message", mapOf("chat_id" to target, "message_id" to 42L, "text" to "Reply"))
            }
        }
        assertThrows<GuardrailViolationException> {
            service.checkPermission("reply_to_message", mapOf("chat_id" to channelA, "message_id" to 42L, "text" to "Reply", "reply_markup" to emptyMap<String, Any>()))
        }
        assertThrows<ReadOnlyModeException> {
            guard(ServerModeProperties(readOnly = true)).checkPermission("reply_to_message", mapOf("chat_id" to channelA))
        }
    }

    @Test
    fun `channel scope rejects every other write tool even for an allowed channel`() {
        val service = scopedPublisher()
        for (tool in listOf("delete_message", "forward_message", "pin_message", "update_profile", "download_media")) {
            assertThrows<GuardrailViolationException> {
                service.checkPermission(tool, mapOf("chat_id" to channelA, "confirmed" to true))
            }
        }
        assertDoesNotThrow { service.checkPermission("get_history", mapOf("chat_id" to channelA)) }
    }

    @Test
    fun `bounded media download is a separate opt-in and does not permit other mutations`() {
        val mode = ServerModeProperties(
            readOnly = false,
            writeScope = ServerModeProperties.WriteScopeProps(
                enabled = true,
                allowedChatIds = listOf(channelA),
                allowMediaDownload = true,
                mediaDownloadRoot = "/encrypted/downloads",
            ),
        )
        val service = guard(mode)
        assertDoesNotThrow {
            service.checkPermission("download_media", mapOf("chat_id" to forumGroup, "message_id" to 42L))
        }
        assertThrows<GuardrailViolationException> {
            service.checkPermission("download_media", mapOf("chat_id" to forumGroup, "message_id" to 42L, "file_path" to "/tmp/x"))
        }
        assertThrows<GuardrailViolationException> {
            service.checkPermission("send_file", mapOf("chat_id" to forumGroup))
        }
        assertThrows<ReadOnlyModeException> {
            guard(mode.copy(readOnly = true)).checkPermission("download_media", mapOf("chat_id" to forumGroup, "message_id" to 42L))
        }
    }

    @Test
    fun `channel scope permits only the fixed unread private folder mutation`() {
        val service = scopedPublisher()
        assertDoesNotThrow { service.checkPermission("ensure_unread_private_folder", emptyMap()) }
        assertThrows<GuardrailViolationException> {
            service.checkPermission("ensure_unread_private_folder", mapOf("include_groups" to true))
        }
        assertThrows<GuardrailViolationException> {
            service.checkPermission("configure_chat_folder", mapOf("title" to "Anything"))
        }
    }

    @Test
    fun `channel scope rejects repeated scheduling while allowing one-time scheduling`() {
        val service = scopedPublisher()
        for (tool in listOf("schedule_message", "reschedule_message")) {
            assertDoesNotThrow { service.checkPermission(tool, mapOf("chat_id" to channelA, "repeat_period_seconds" to 0)) }
            assertThrows<GuardrailViolationException> {
                service.checkPermission(tool, mapOf("chat_id" to channelA, "repeat_period_seconds" to 60))
            }
        }
    }

    @Test
    fun `enabled scope with no channels denies all publishing`() {
        val service = guard(ServerModeProperties(
            readOnly = false,
            writeScope = ServerModeProperties.WriteScopeProps(enabled = true),
        ))
        assertThrows<GuardrailViolationException> {
            service.checkPermission("send_message", mapOf("chat_id" to channelA))
        }
    }

    // ── Confirmation mode ───────────────────────────────────────────────────

    @Test
    fun `confirmation mode blocks destructive tool without confirmation`() {
        val service = guard(
            ServerModeProperties(
                readOnly = false,
                confirmation = ServerModeProperties.ConfirmationProps(enabled = true),
            ),
        )

        assertThrows<ConfirmationRequiredException> {
            service.checkPermission("delete_message", emptyMap())
        }
    }

    @Test
    fun `confirmation mode allows destructive tool with confirmed=true`() {
        val service = guard(
            ServerModeProperties(
                readOnly = false,
                confirmation = ServerModeProperties.ConfirmationProps(enabled = true),
            ),
        )

        assertDoesNotThrow {
            service.checkPermission("delete_message", mapOf("confirmed" to true))
        }
    }

    @Test
    fun `confirmation mode allows destructive tool with confirmed=true string`() {
        val service = guard(
            ServerModeProperties(
                readOnly = false,
                confirmation = ServerModeProperties.ConfirmationProps(enabled = true),
            ),
        )

        assertDoesNotThrow {
            service.checkPermission("delete_message", mapOf("confirmed" to "true"))
        }
    }

    @Test
    fun `guardrail-loosening register_internal_chat requires confirmation`() {
        val service = guard(
            ServerModeProperties(
                readOnly = false,
                confirmation = ServerModeProperties.ConfirmationProps(enabled = true),
            ),
        )

        assertThrows<ConfirmationRequiredException> {
            service.checkPermission("register_internal_chat", mapOf("chat_id" to 1L))
        }
        assertDoesNotThrow {
            service.checkPermission("register_internal_chat", mapOf("chat_id" to 1L, "confirmed" to true))
        }
    }

    @Test
    fun `confirmation mode allows non-destructive write tools without confirmation`() {
        val service = guard(
            ServerModeProperties(
                readOnly = false,
                confirmation = ServerModeProperties.ConfirmationProps(enabled = true),
            ),
        )

        assertDoesNotThrow {
            service.checkPermission("send_message", emptyMap())
        }
        assertDoesNotThrow {
            service.checkPermission("create_topic", emptyMap())
        }
        assertDoesNotThrow {
            service.checkPermission("edit_forum_topic", emptyMap())
        }
        assertDoesNotThrow {
            service.checkPermission("close_forum_topic", emptyMap())
        }
        assertDoesNotThrow {
            service.checkPermission("reopen_forum_topic", emptyMap())
        }
        assertDoesNotThrow {
            service.checkPermission("set_forum_topics_enabled", emptyMap())
        }
    }

    @Test
    fun `confirmation mode blocks delete_profile_photo without confirmation`() {
        val service = guard(
            ServerModeProperties(
                readOnly = false,
                confirmation = ServerModeProperties.ConfirmationProps(enabled = true),
            ),
        )

        assertThrows<ConfirmationRequiredException> {
            service.checkPermission("delete_profile_photo", emptyMap())
        }
    }

    @Test
    fun `configured destructive tools add to the defaults instead of replacing them`() {
        val service = guard(
            ServerModeProperties(
                readOnly = false,
                confirmation = ServerModeProperties.ConfirmationProps(
                    enabled = true,
                    destructiveTools = listOf("send_message"),
                ),
            ),
        )

        // The configured tool now requires confirmation…
        assertThrows<ConfirmationRequiredException> {
            service.checkPermission("send_message", emptyMap())
        }
        // …and the defaults still do. In 1.18.1 this list replaced them.
        assertThrows<ConfirmationRequiredException> {
            service.checkPermission("delete_message", emptyMap())
        }
        assertTrue(service.isDestructiveTool("ban_user"))
    }

    @Test
    fun `configured gating lists must name write tools`() {
        val error = assertThrows<IllegalArgumentException> {
            guard(
                ServerModeProperties(
                    readOnly = false,
                    confirmation = ServerModeProperties.ConfirmationProps(destructiveTools = listOf("delete_mesage")),
                ),
            )
        }
        assertTrue(error.message.orEmpty().contains("delete_mesage"))
        assertThrows<IllegalArgumentException> {
            guard(
                ServerModeProperties(
                    readOnly = false,
                    confirmation = ServerModeProperties.ConfirmationProps(highRiskTools = listOf("get_history")),
                ),
            )
        }
    }

    @Test
    fun `approval scope decides which tools need a human`() {
        fun scoped(scope: ServerModeProperties.ApprovalScope, highRisk: List<String> = emptyList()) = guard(
            ServerModeProperties(
                readOnly = false,
                confirmation = ServerModeProperties.ConfirmationProps(approvalScope = scope, highRiskTools = highRisk),
            ),
        )

        val destructive = scoped(ServerModeProperties.ApprovalScope.DESTRUCTIVE)
        assertTrue(destructive.requiresHumanApproval("ban_user"))
        assertFalse(destructive.requiresHumanApproval("forward_message"))

        val highRisk = scoped(ServerModeProperties.ApprovalScope.HIGH_RISK, highRisk = listOf("send_voice"))
        OperationGuardService.DEFAULT_HIGH_RISK_TOOLS.forEach { assertTrue(highRisk.requiresHumanApproval(it), it) }
        assertTrue(highRisk.requiresHumanApproval("send_voice"))
        assertTrue(highRisk.requiresHumanApproval("delete_message"))
        assertFalse(highRisk.requiresHumanApproval("send_message"))
        assertFalse(highRisk.requiresHumanApproval("send_reaction"))
        assertEquals("high-risk", highRisk.riskLabel("forward_message"))
        assertEquals("destructive", highRisk.riskLabel("delete_message"))

        val all = scoped(ServerModeProperties.ApprovalScope.ALL_WRITES)
        OperationGuardService.WRITE_TOOLS.forEach { assertTrue(all.requiresHumanApproval(it), it) }
        assertFalse(all.requiresHumanApproval("get_history"))
    }

    @Test
    fun `high-risk tools do not demand the confirmed argument`() {
        val service = guard(
            ServerModeProperties(readOnly = false, confirmation = ServerModeProperties.ConfirmationProps(enabled = true)),
        )
        // Approval is a separate, human gate; existing clients never sent
        // `confirmed` for these, so requiring it would break them.
        assertDoesNotThrow { service.checkPermission("forward_message", emptyMap()) }
    }

    @Test
    fun `destructive hints follow the effective approval set`() {
        val policy = OperationGuardService.RiskPolicy(
            ServerModeProperties.ConfirmationProps(destructiveTools = listOf("send_message")),
        )
        assertTrue(OperationGuardService.annotationsFor("send_message", policy).destructiveHint())
        assertTrue(OperationGuardService.annotationsFor("delete_message", policy).destructiveHint())
        assertTrue(OperationGuardService.annotationsFor("forward_message", policy).destructiveHint())
        assertTrue(OperationGuardService.annotationsFor("press_inline_button", policy).destructiveHint())
        assertFalse(OperationGuardService.annotationsFor("send_reaction", policy).destructiveHint())
        assertFalse(OperationGuardService.annotationsFor("get_history", policy).destructiveHint())

        val narrow = OperationGuardService.RiskPolicy(
            ServerModeProperties.ConfirmationProps(approvalScope = ServerModeProperties.ApprovalScope.DESTRUCTIVE),
        )
        assertFalse(OperationGuardService.annotationsFor("forward_message", narrow).destructiveHint())
        assertTrue(OperationGuardService.annotationsFor("ban_user", narrow).destructiveHint())
    }

    // ── Helper methods ──────────────────────────────────────────────────────

    @Test
    fun `isWriteTool identifies write tools correctly`() {
        val service = guard(ServerModeProperties(readOnly = false))

        assertTrue(service.isWriteTool("send_message"))
        assertTrue(service.isWriteTool("ban_user"))
        assertTrue(service.isWriteTool("send_voice"))
        assertTrue(service.isWriteTool("transcribe_voice_note"))
        assertTrue(service.isWriteTool("register_internal_chat"))
        assertTrue(service.isWriteTool("configure_chat_folder"))
        assertTrue(service.isWriteTool("delete_chat_folder"))
        assertTrue(service.isWriteTool("schedule_message"))
        assertTrue(service.isWriteTool("reschedule_message"))
        assertTrue(service.isWriteTool("cancel_scheduled_message"))
        assertTrue(service.isWriteTool("save_draft"))
        assertTrue(service.isWriteTool("create_topic"))
        assertTrue(service.isWriteTool("edit_forum_topic"))
        assertTrue(service.isWriteTool("close_forum_topic"))
        assertTrue(service.isWriteTool("reopen_forum_topic"))
        assertTrue(service.isWriteTool("set_forum_topics_enabled"))
        assertTrue(service.isWriteTool("delete_profile_photo"))
        assertFalse(service.isWriteTool("get_history"))
        assertFalse(service.isWriteTool("list_contacts"))
    }

    @Test
    fun `isReadOnly reflects config`() {
        val readOnlyService = guard(ServerModeProperties(readOnly = true))
        val normalService = guard(ServerModeProperties(readOnly = false))

        assertTrue(readOnlyService.isReadOnly())
        assertFalse(normalService.isReadOnly())
    }
}
