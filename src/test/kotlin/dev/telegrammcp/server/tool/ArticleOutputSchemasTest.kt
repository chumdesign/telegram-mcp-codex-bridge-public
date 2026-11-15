package dev.telegrammcp.server.tool

import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArticleOutputSchemasTest {
    @Test
    fun `recursive Article contract validates native table links and nested formatting`() {
        val schema = ToolSupport.definition("get_article", "Article", mapOf("type" to "object"),
            ArticleOutputSchemas.article).outputSchema()
        val validator = DefaultJsonSchemaValidator()
        val checked = validator.validateSchema(schema)
        assertTrue(checked.valid(), checked.errorMessage())
        val rich = mapOf("type" to "link", "url" to "https://example.org", "children" to listOf(
            mapOf("type" to "bold", "children" to listOf(mapOf("type" to "text", "text" to "Example"))),
        ))
        val cell = mapOf("text" to "Example", "richText" to rich, "isHeader" to false,
            "colspan" to 1, "rowspan" to 1, "align" to "left", "valign" to "top")
        val table = mapOf("type" to "table", "rows" to listOf(listOf(cell, cell, cell)), "isCompact" to true)
        val result = mapOf("data" to mapOf("chatId" to 42, "messageId" to 7, "isFull" to true,
            "blocks" to listOf(mapOf("type" to "quote", "blocks" to listOf(table)))),
            "meta" to mapOf("untrustedTelegramContent" to true, "escapedCharacterCount" to 0))
        val validation = validator.validate(schema, result)
        assertTrue(validation.valid(), validation.errorMessage())
        assertFalse(validator.validate(ArticleOutputSchemas.article, mapOf("chatId" to 42, "messageId" to 7,
            "isFull" to true, "blocks" to listOf(table + ("rows" to "flattened")))).valid())
        val error = validator.validate(schema, ToolSupport.errorResult(IllegalStateException("unavailable")).structuredContent())
        assertTrue(error.valid(), error.errorMessage())
    }
}
