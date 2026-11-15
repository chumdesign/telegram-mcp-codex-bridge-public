package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.ArticleDraft
import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArticleContentFactoryTest {
    @Test
    fun `embeds a real photo before verbatim article paragraphs`() {
        val text = "Меріл Стріп якось сказала:\n\nДозволь усьому розвалитися — відпусти.\n\nЩось краще вже чекає."
        val content = ArticleContentFactory.build(text, "C:\\publish-ready\\meryl.png")
        val source = assertIs<TdApi.RichMessageSourceBlocks>(content.message.source)
        val photoBlock = assertIs<TdApi.InputPageBlockPhoto>(source.blocks[0])
        val localFile = assertIs<TdApi.InputFileLocal>(photoBlock.photo.photo)
        assertEquals("C:\\publish-ready\\meryl.png", localFile.path)
        assertEquals(4, source.blocks.size)
        val reconstructed = source.blocks.drop(1).joinToString("\n\n") { block ->
            assertIs<TdApi.RichTextPlain>(assertIs<TdApi.InputPageBlockParagraph>(block).text).text
        }
        assertEquals(text, reconstructed)
    }

    @Test
    fun `rejects an empty article and text beyond the Telegram rich message limit`() {
        assertThrows<InvalidToolInputException> { ArticleContentFactory.build("  ", "/safe/photo.png") }
        assertThrows<InvalidToolInputException> { ArticleContentFactory.build("x".repeat(32_769), "/safe/photo.png") }
    }

    @Test
    fun `structured table has real TDLib rows cells and linked site names`() {
        val draft = ArticleDraft(blocks = listOf(
            mapOf("type" to "heading", "size" to 1, "text" to "Подборка"),
            mapOf("type" to "paragraph", "text" to listOf(
                mapOf("type" to "bold", "text" to "Жирный"),
                " и ", mapOf("type" to "italic", "text" to "курсив"),
            )),
            mapOf("type" to "table", "bordered" to true, "rows" to listOf(
                listOf(mapOf("text" to "Сайт", "header" to true), "Формат", "Особенности"),
                listOf(
                    mapOf("text" to listOf(mapOf("type" to "link", "url" to "https://example.org", "text" to "Example"))),
                    "Справочник", "Проверка",
                ),
            )),
        ))
        val (content, validation) = ArticleContentFactory.build(draft, ArticleContentFactory.DEFAULT_LIMITS)
        val source = assertIs<TdApi.RichMessageSourceBlocks>(content.message.source)
        assertIs<TdApi.InputPageBlockSectionHeading>(source.blocks[0])
        val table = assertIs<TdApi.InputPageBlockTable>(source.blocks[2])
        assertEquals(2, table.cells.size)
        assertEquals(3, table.cells[0].size)
        val link = assertIs<TdApi.RichTextUrl>(
            assertIs<TdApi.RichTexts>(table.cells[1][0].text).texts[0],
        )
        assertEquals("https://example.org", link.url)
        assertEquals("Example", assertIs<TdApi.RichTextPlain>(link.text).text)
        assertEquals(1, validation.tableCount)
    }

    @Test
    fun `markdown and HTML tables retain native cells and inline links`() {
        val markdown = "# Sites\n\n**Strong** and *emphasis*\n\n| Site | Format | Notes |\n|---|---|---|\n| [Example](https://example.org) | Wiki | Linked |"
        val html = "<h1>Sites</h1><p><strong>Strong</strong> and <em>emphasis</em></p>" +
            "<table><tr><th>Site</th><th>Format</th><th>Notes</th></tr>" +
            "<tr><td><a href=\"https://example.org\">Example</a></td><td>Wiki</td><td>Linked</td></tr></table>"
        for (draft in listOf(ArticleDraft(richMarkdown = markdown), ArticleDraft(richHtml = html))) {
            val (content, validation) = ArticleContentFactory.build(draft, ArticleContentFactory.DEFAULT_LIMITS)
            val table = assertIs<TdApi.InputPageBlockTable>(
                assertIs<TdApi.RichMessageSourceBlocks>(content.message.source).blocks.last(),
            )
            assertEquals(3, table.cells[1].size)
            assertTrue(validation.textLength > 20)
        }
    }

    @Test
    fun `HTML fragment with top-level inline tags keeps paragraph boundaries and formatting`() {
        val (content, _) = ArticleContentFactory.build(
            ArticleDraft(richHtml = "<b>Title</b>\n\nPlain <i>italic</i> text.\n\nLast paragraph."),
            ArticleContentFactory.DEFAULT_LIMITS,
        )
        val blocks = assertIs<TdApi.RichMessageSourceBlocks>(content.message.source).blocks
        assertEquals(3, blocks.size)
        assertIs<TdApi.RichTextBold>(assertIs<TdApi.RichTexts>(
            assertIs<TdApi.InputPageBlockParagraph>(blocks[0]).text,
        ).texts[0])
        assertIs<TdApi.RichTextItalic>(assertIs<TdApi.RichTexts>(
            assertIs<TdApi.InputPageBlockParagraph>(blocks[1]).text,
        ).texts[1])
    }

    @Test
    fun `preflight handles more than ordinary 4096 characters without image`() {
        val (content, validation) = ArticleContentFactory.build(
            ArticleDraft(text = "A".repeat(5_200)), ArticleContentFactory.DEFAULT_LIMITS,
        )
        assertEquals(5_200, validation.textLength)
        assertEquals(0, validation.mediaCount)
        assertEquals(1, assertIs<TdApi.RichMessageSourceBlocks>(content.message.source).blocks.size)
    }

    @Test
    fun `divider is valid native block and counts toward Telegram block limit`() {
        val (content, validation) = ArticleContentFactory.build(
            ArticleDraft(blocks = listOf(
                mapOf("type" to "paragraph", "text" to "Before"),
                mapOf("type" to "divider"),
                mapOf("type" to "paragraph", "text" to "After"),
            )), ArticleContentFactory.DEFAULT_LIMITS,
        )
        assertEquals(3, validation.blockCount)
        assertIs<TdApi.InputPageBlockDivider>(
            assertIs<TdApi.RichMessageSourceBlocks>(content.message.source).blocks[1],
        )
    }

    @Test
    fun `invalid sources and unsupported formatting fail instead of flattening`() {
        assertThrows<InvalidToolInputException> {
            ArticleContentFactory.build(ArticleDraft(text = "x", richHtml = "<p>x</p>"), ArticleContentFactory.DEFAULT_LIMITS)
        }
        assertThrows<InvalidToolInputException> {
            ArticleContentFactory.build(ArticleDraft(richHtml = "<script>alert(1)</script>"), ArticleContentFactory.DEFAULT_LIMITS)
        }
        assertThrows<InvalidToolInputException> {
            ArticleContentFactory.build(ArticleDraft(richMarkdown = "![image](https://example.org/x.jpg)"), ArticleContentFactory.DEFAULT_LIMITS)
        }
        assertThrows<InvalidToolInputException> {
            ArticleContentFactory.build(ArticleDraft(richHtml = "<ol start=\"4\"><li>Item</li></ol>"), ArticleContentFactory.DEFAULT_LIMITS)
        }
        assertThrows<InvalidToolInputException> {
            ArticleContentFactory.build(ArticleDraft(richHtml = "<table><tr style=\"color:red\"><td>Cell</td></tr></table>"), ArticleContentFactory.DEFAULT_LIMITS)
        }
        assertThrows<InvalidToolInputException> {
            ArticleContentFactory.build(ArticleDraft(blocks = listOf(mapOf("type" to "table",
                "rows" to listOf(listOf("1", "2", "3", "4"))))),
                ArticleContentFactory.DEFAULT_LIMITS.copy(maxTableColumns = 3))
        }
    }
}
