package dev.telegrammcp.server.service

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeApprovalPromptTest {
    @Test fun `mac approval keeps operation parameters outside executable AppleScript`() {
        val untrusted = "\" & do shell script \"touch /tmp/unwanted\" & \""
        val command = NativeApprovalPrompt().macCommand("delete_message", untrusted, Duration.ofSeconds(60))
        assertEquals("/usr/bin/osascript", command[0])
        assertFalse(command[2].contains(untrusted))
        assertTrue(command[3].contains(untrusted))
        assertTrue(command[2].contains("default button \"Отмена\""))
        assertTrue(command[2].contains("if gave up of answer then return \"denied\""))
        assertEquals("60", command.last())
    }
}
