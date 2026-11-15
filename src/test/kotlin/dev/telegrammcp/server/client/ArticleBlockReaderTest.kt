package dev.telegrammcp.server.client

import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ArticleBlockReaderTest {
    @Test
    fun `reads table cells and nested links without collapsing rows`() {
        val title = TdApi.PageBlockTableCell(
            TdApi.RichTextPlain("Сайт"), true, 1, 1,
            TdApi.PageBlockHorizontalAlignmentLeft(), TdApi.PageBlockVerticalAlignmentTop(),
        )
        val site = TdApi.PageBlockTableCell(
            TdApi.RichTextUrl(TdApi.RichTextBold(TdApi.RichTextPlain("Example")), "https://example.org", false),
            false, 1, 1, TdApi.PageBlockHorizontalAlignmentLeft(), TdApi.PageBlockVerticalAlignmentTop(),
        )
        val table = TdApi.PageBlockTable(
            TdApi.RichTextPlain("Каталог"), arrayOf(arrayOf(title), arrayOf(site)), true, false, true,
        )
        val result = ArticleBlockReader.block(table)
        assertEquals("table", result.type)
        assertEquals(true, result.isCompact)
        val preserved = ArticleBlockPreserver.blocks(arrayOf(table)).single() as TdApi.InputPageBlockTable
        assertEquals(true, preserved.isCompact)
        assertEquals("Сайт\nExample", ArticleBlockReader.plainText(table))
        assertEquals(2, result.rows?.size)
        assertEquals("Сайт", result.rows?.get(0)?.get(0)?.text)
        assertEquals("Example", result.rows?.get(1)?.get(0)?.text)
        val link = assertNotNull(result.rows?.get(1)?.get(0)?.richText)
        assertEquals("link", link.type)
        assertEquals("https://example.org", link.url)
        assertEquals("bold", link.children?.single()?.type)
    }
}
