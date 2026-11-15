package dev.telegrammcp.server.api

import dev.telegrammcp.server.auth.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TelegramAuthPasswordControllerTest {
    @Test
    fun `HTTP retries use TDLib even after the old interaction future is consumed`() {
        val holder = TelegramAuthStateHolder()
        holder.setState(AuthState.WaitingPassword())
        holder.awaitPassword()
        holder.submitPassword("old-attempt")
        val orchestrator = mockk<TelegramAuthOrchestrator>()
        every { orchestrator.submitPassword(any()) } returnsMany listOf("Telegram rejected the cloud password", null)
        val controller = TelegramAuthController(orchestrator, holder, mockk(), AuthWizardProperties(enabled = true))

        assertEquals(409, controller.submitPassword(SubmitPasswordRequest("first")).statusCode.value())
        assertEquals(200, controller.submitPassword(SubmitPasswordRequest("retry")).statusCode.value())
        verify(exactly = 2) { orchestrator.submitPassword(any()) }
    }
}
