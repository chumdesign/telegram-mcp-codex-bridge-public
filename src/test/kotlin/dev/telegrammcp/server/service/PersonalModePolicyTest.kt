package dev.telegrammcp.server.service

import dev.telegrammcp.server.config.AntiSpamProperties
import dev.telegrammcp.server.config.ServerModeProperties
import dev.telegrammcp.server.config.ServerModeProperties.ApprovalMode
import dev.telegrammcp.server.exception.GuardrailViolationException
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersonalModePolicyTest {
    private val props = ServerModeProperties(
        readOnly = false,
        writeScope = ServerModeProperties.WriteScopeProps(enabled = true, personalMode = true,
            allowMediaDownload = true, mediaDownloadRoot = "C:/local/telegram-downloads"),
        confirmation = ServerModeProperties.ConfirmationProps(approval = ApprovalMode.NATIVE),
    )
    private val antiSpamProps = AntiSpamProperties(enabled = false, overridesFile = null)
    private val guard = OperationGuardService(props, AntiSpamGuardService(antiSpamProps,
        SimpleMeterRegistry(), AntiSpamPolicyService(antiSpamProps)))

    @Test fun `ordinary writes accept arbitrary chats and attachments`() {
        for (tool in listOf("send_message", "reply_to_message", "send_file", "send_media_album", "send_article", "edit_article",
            "schedule_message", "edit_message", "mark_as_read", "configure_chat_folder", "transcribe_voice_note")) {
            assertDoesNotThrow { guard.checkPermission(tool, mapOf("chat_id" to "@anyuser")) }
            assertFalse(guard.isDestructiveTool(tool))
        }
    }

    @Test fun `destructive and privacy tools need a real approval route`() {
        for (tool in listOf("delete_message", "ban_user", "set_admin_rights", "set_privacy_settings",
            "join_chat_by_link", "cancel_scheduled_message", "upgrade_group_to_supergroup", "press_inline_button",
            "clear_draft")) {
            assertTrue(guard.isDestructiveTool(tool))
            assertDoesNotThrow { guard.checkPermission(tool, emptyMap()) }
        }
    }

    @Test fun `the tool that weakens its own anti-spam rules remains denied`() {
        assertThrows<GuardrailViolationException> {
            guard.checkPermission("register_internal_chat", emptyMap())
        }
    }

    @Test fun `recurring posts and explicit article image removal require human approval`() {
        val repeat = mapOf("repeat_period_seconds" to 86_400)
        assertDoesNotThrow { guard.checkPermission("schedule_message", repeat) }
        assertTrue(guard.needsHumanApproval("schedule_message", repeat))
        assertTrue(guard.needsHumanApproval("reschedule_message", repeat))
        assertFalse(guard.needsHumanApproval("schedule_message", mapOf("repeat_period_seconds" to 0)))
        assertTrue(guard.needsHumanApproval("edit_article", mapOf("remove_photo" to true)))
        assertFalse(guard.needsHumanApproval("edit_article", mapOf("photo_path" to "cover.jpg")))
    }

    @Test fun `personal mode cannot run without native confirmation`() {
        assertThrows<IllegalArgumentException> {
            ServerModeProperties(writeScope = ServerModeProperties.WriteScopeProps(enabled = true, personalMode = true))
        }
    }
}
