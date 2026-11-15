package dev.telegrammcp.server.cli

import org.junit.jupiter.api.Test
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.SimpleCommandLinePropertySource
import org.springframework.core.env.StandardEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TelegramMcpCliTest {

    @Test
    fun `auth wizard security properties override configured server defaults`() {
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(MapPropertySource("configured", mapOf(
            "server.address" to "0.0.0.0",
            "server.port" to "8080",
            "spring.ai.mcp.server.enabled" to "true",
            "auth-wizard.enabled" to "false",
        )))
        environment.propertySources.addFirst(SimpleCommandLinePropertySource(
            *TelegramMcpCli.authWizardArguments("default", "qr", "test-nonce"),
        ))
        assertEquals("127.0.0.1", environment.getProperty("server.address"))
        assertEquals("0", environment.getProperty("server.port"))
        assertEquals("false", environment.getProperty("spring.ai.mcp.server.enabled"))
        assertEquals("true", environment.getProperty("auth-wizard.enabled"))
    }

    @Test
    fun `auth wizard arguments preserve the selected account method and nonce`() {
        val source = SimpleCommandLinePropertySource(
            *TelegramMcpCli.authWizardArguments("work", "phone", "test-nonce"),
        )
        assertEquals("work", source.getProperty("auth-wizard.account-label"))
        assertEquals("phone", source.getProperty("auth-wizard.method"))
        assertEquals("test-nonce", source.getProperty("auth-wizard.nonce"))
    }

    @Test
    fun `CLI transport takes precedence over environment`() {
        val invocation = TelegramMcpCli.resolveServerInvocation(
            listOf("--transport", "stdio", "--spring.profiles.active=test"),
            "streamable-http",
        )

        assertEquals(TelegramMcpCli.Transport.STDIO, invocation.transport)
        assertEquals(listOf("--spring.profiles.active=test"), invocation.remaining)
    }

    @Test
    fun `environment selects stdio when CLI flag is absent`() {
        val invocation = TelegramMcpCli.resolveServerInvocation(emptyList(), "stdio")

        assertEquals(TelegramMcpCli.Transport.STDIO, invocation.transport)
    }

    @Test
    fun `legacy default stays streamable HTTP`() {
        val invocation = TelegramMcpCli.resolveServerInvocation(emptyList(), null)

        assertEquals(TelegramMcpCli.Transport.HTTP, invocation.transport)
    }

    @Test
    fun `unknown transport fails closed`() {
        assertFailsWith<IllegalStateException> {
            TelegramMcpCli.resolveServerInvocation(listOf("--transport=websocket"), null)
        }
    }
}
