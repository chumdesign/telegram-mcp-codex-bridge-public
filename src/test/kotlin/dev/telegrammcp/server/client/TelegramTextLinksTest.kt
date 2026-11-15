package dev.telegrammcp.server.client

import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramTextLinksTest {
    private fun entity(offset: Int, length: Int, type: TdApi.TextEntityType) = TdApi.TextEntity().apply {
        this.offset = offset
        this.length = length
        this.type = type
    }

    @Test
    fun `extracts linked labels and bare URLs without losing UTF-16 offsets`() {
        val text = "😀 UA https://example.org"
        val content = TdApi.MessageText().apply {
            this.text = TdApi.FormattedText(text, arrayOf(
                entity(3, 2, TdApi.TextEntityTypeTextUrl("https://example.org/article/226")),
                entity(6, 19, TdApi.TextEntityTypeUrl()),
            ))
        }

        val links = extractTelegramTextLinks(content)
        assertEquals(listOf("UA", "https://example.org"), links.map { it.text })
        assertEquals(listOf("https://example.org/article/226", "https://example.org"), links.map { it.url })
        assertEquals(listOf(3, 6), links.map { it.offset })
    }

    @Test
    fun `extracts links from captions and skips malformed ranges`() {
        val caption = TdApi.FormattedText("Фото UA", arrayOf(
            entity(5, 2, TdApi.TextEntityTypeTextUrl("https://example.org/photo")),
            entity(100, 2, TdApi.TextEntityTypeUrl()),
        ))
        val content = TdApi.MessagePhoto().apply { this.caption = caption }

        val links = extractTelegramTextLinks(content)
        assertEquals(1, links.size)
        assertEquals("https://example.org/photo", links.single().url)
        assertTrue(extractTelegramTextLinks(TdApi.MessageSticker()).isEmpty())
    }
}
