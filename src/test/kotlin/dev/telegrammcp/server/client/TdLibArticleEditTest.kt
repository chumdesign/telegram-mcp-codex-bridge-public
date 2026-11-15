package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.ArticleDraft
import dev.telegrammcp.server.model.ParseMode
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.ratelimiter.RateLimiter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import it.tdlight.client.GenericResultHandler
import it.tdlight.client.Result
import it.tdlight.client.SimpleTelegramClient
import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TdLibArticleEditTest {
    private val client = mockk<SimpleTelegramClient>()
    private val backups = mockk<EditBackupStore>()
    private val requests = mutableListOf<TdApi.Function<*>>()
    private val service = TdLibClientService(
        client, RateLimiter.ofDefaults("article-edit"), CircuitBreaker.ofDefaults("article-edit"), SimpleMeterRegistry(),
        editBackupStore = backups,
    )

    init { every { backups.save(any(), any()) } returns Path.of("backup.tdapi") }

    private fun article(id: Long) = TdApi.Message().apply {
        this.id = id
        chatId = 42L
        content = TdApi.MessageRichMessage(TdApi.RichMessage().apply {
            blocks = arrayOf(TdApi.PageBlockParagraph(TdApi.RichTextPlain("Before")))
            isFull = true
        })
    }

    private var existing = article(77)

    private fun respond(editable: Boolean = true) {
        every { client.send(any<TdApi.Function<TdApi.Object>>(), any<GenericResultHandler<TdApi.Object>>()) } answers {
            val request = firstArg<TdApi.Function<TdApi.Object>>()
            requests += request
            val response: TdApi.Object = when (request) {
                is TdApi.GetMessages -> TdApi.Messages().apply { messages = arrayOf(existing) }
                is TdApi.GetFullRichMessage -> (existing.content as TdApi.MessageRichMessage).message
                is TdApi.GetMessageProperties -> TdApi.MessageProperties().apply { canBeEdited = editable }
                is TdApi.GetOption -> TdApi.OptionValueInteger(when (request.name) {
                    "rich_message_text_length_max" -> 32_768
                    "rich_message_block_count_max" -> 500
                    "rich_message_depth_max" -> 16
                    "rich_message_media_count_max" -> 50
                    "rich_message_table_column_count_max" -> 20
                    else -> error("Unexpected option ${request.name}")
                })
                is TdApi.EditMessageText -> article(77)
                is TdApi.GetChat -> TdApi.Chat().apply { title = "Saved" }
                else -> error("Unexpected request: $request")
            }
            secondArg<GenericResultHandler<TdApi.Object>>().onResult(Result.of(response))
        }
    }

    @Test
    fun `edit uses native EditMessageText with RichMessage blocks and preserves message ID`() {
        respond()
        val result = service.editArticle(42, 77, ArticleDraft(blocks = listOf(
            mapOf("type" to "heading", "text" to "After"),
            mapOf("type" to "table", "rows" to listOf(listOf("One", "Two", "Three"))),
        )))
        val edit = requests.filterIsInstance<TdApi.EditMessageText>().single()
        assertEquals(77, edit.messageId)
        val source = assertIs<TdApi.RichMessageSourceBlocks>(
            assertIs<TdApi.InputMessageRichMessage>(edit.inputMessageContent).message.source,
        )
        assertIs<TdApi.InputPageBlockTable>(source.blocks[1])
        assertEquals(77, result.messageId)
        assertTrue(requests.none { it is TdApi.DeleteMessages || it is TdApi.SendMessage })
    }

    @Test
    fun `non-editable Article is rejected before mutation`() {
        respond(editable = false)
        assertThrows<InvalidToolInputException> { service.editArticle(42, 77, ArticleDraft(text = "After")) }
        assertTrue(requests.none { it is TdApi.EditMessageText })
    }

    private fun coveredArticle(): TdApi.Message {
        val file = TdApi.File().apply { id = 321; size = 1234 }
        val size = TdApi.PhotoSize().apply { photo = file; width = 100; height = 60 }
        val photo = TdApi.Photo().apply { sizes = arrayOf(size) }
        return article(77).apply {
            (content as TdApi.MessageRichMessage).message.blocks = arrayOf(
                TdApi.PageBlockPhoto(photo, null, "", false),
                TdApi.PageBlockParagraph(TdApi.RichTextBold(TdApi.RichTextPlain("Original text"))),
            )
        }
    }

    @Test
    fun `article photo replacement preserves original rich text`() {
        existing = coveredArticle()
        respond()
        service.editArticle(42, 77, ArticleDraft(photoPath = "C:\\ready\\new.png"))
        val source = assertIs<TdApi.RichMessageSourceBlocks>(
            assertIs<TdApi.InputMessageRichMessage>(requests.filterIsInstance<TdApi.EditMessageText>().single().inputMessageContent).message.source,
        )
        assertEquals("C:\\ready\\new.png", assertIs<TdApi.InputFileLocal>(
            assertIs<TdApi.InputPageBlockPhoto>(source.blocks[0]).photo.photo,
        ).path)
        assertIs<TdApi.RichTextBold>(assertIs<TdApi.InputPageBlockParagraph>(source.blocks[1]).text)
    }

    @Test
    fun `article text replacement keeps existing photo by file ID`() {
        existing = coveredArticle()
        respond()
        service.editArticle(42, 77, ArticleDraft(text = "Replacement"))
        val source = assertIs<TdApi.RichMessageSourceBlocks>(
            assertIs<TdApi.InputMessageRichMessage>(requests.filterIsInstance<TdApi.EditMessageText>().single().inputMessageContent).message.source,
        )
        assertEquals(321, assertIs<TdApi.InputFileId>(
            assertIs<TdApi.InputPageBlockPhoto>(source.blocks[0]).photo.photo,
        ).id)
        assertEquals("Replacement", assertIs<TdApi.RichTextPlain>(
            assertIs<TdApi.InputPageBlockParagraph>(source.blocks[1]).text,
        ).text)
    }

    @Test
    fun `text post can be converted to Article with photo without changing message ID`() {
        existing = TdApi.Message().apply {
            id = 77; chatId = 42
            content = TdApi.MessageText().apply { text = TdApi.FormattedText("Original", emptyArray()) }
        }
        respond()
        val edited = service.editArticle(42, 77, ArticleDraft(photoPath = "C:\\ready\\new.png"))
        val source = assertIs<TdApi.RichMessageSourceBlocks>(
            assertIs<TdApi.InputMessageRichMessage>(requests.filterIsInstance<TdApi.EditMessageText>().single().inputMessageContent).message.source,
        )
        assertIs<TdApi.InputPageBlockPhoto>(source.blocks[0])
        assertEquals("Original", assertIs<TdApi.RichTextPlain>(
            assertIs<TdApi.InputPageBlockParagraph>(source.blocks[1]).text,
        ).text)
        assertEquals(77, edited.messageId)
    }

    @Test
    fun `edit_message on a simple Article retains its photo block`() {
        existing = coveredArticle()
        respond()
        service.editMessage(42, 77, "Changed", ParseMode.PLAIN)
        val source = assertIs<TdApi.RichMessageSourceBlocks>(
            assertIs<TdApi.InputMessageRichMessage>(requests.filterIsInstance<TdApi.EditMessageText>().single().inputMessageContent).message.source,
        )
        assertEquals(321, assertIs<TdApi.InputFileId>(
            assertIs<TdApi.InputPageBlockPhoto>(source.blocks[0]).photo.photo,
        ).id)
    }

    @Test
    fun `edit_message refuses incomplete Article before mutation`() {
        existing = coveredArticle()
        (existing.content as TdApi.MessageRichMessage).message.isFull = false
        respond()
        assertThrows<InvalidToolInputException> { service.editMessage(42, 77, "Changed", ParseMode.PLAIN) }
        assertTrue(requests.none { it is TdApi.EditMessageText })
    }
}
