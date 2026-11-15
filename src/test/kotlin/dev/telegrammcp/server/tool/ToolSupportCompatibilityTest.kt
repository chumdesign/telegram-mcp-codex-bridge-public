package dev.telegrammcp.server.tool

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolSupportCompatibilityTest {
    private val mapper = ObjectMapper()

    @Test
    fun `successful wire response omits priority and retains untrusted content markers`() {
        val result = ToolSupport.jsonResult(mapOf("example" to "public fixture"), mapper)
        val wire = mapper.readTree(mapper.writeValueAsString(result))
        val content = wire["content"][0]
        assertFalse(content["annotations"].has("priority"))
        assertEquals("user", content["annotations"]["audience"][0].asText())
        assertTrue(content["_meta"]["io.github.tolboy/untrusted-content"].asBoolean())
        assertTrue(wire["_meta"]["io.github.tolboy/untrusted-content"].asBoolean())
        assertTrue(wire["structuredContent"]["meta"]["untrustedTelegramContent"].asBoolean())
        assertEquals("public fixture", wire["structuredContent"]["data"]["example"].asText())
    }

    @Test
    fun `error wire response omits priority and retains audience and untrusted marker`() {
        val result = ToolSupport.errorText("Public test error")
        val wire = mapper.readTree(mapper.writeValueAsString(result))
        val content = wire["content"][0]
        assertTrue(wire["isError"].asBoolean())
        assertFalse(content["annotations"].has("priority"))
        assertEquals(listOf(McpSchema.Role.USER), (result.content().single() as McpSchema.TextContent).annotations().audience())
        assertTrue(content["_meta"]["io.github.tolboy/untrusted-content"].asBoolean())
    }
}
