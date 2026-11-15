package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.InvalidToolInputException
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

class TdLibPhotoEditTest {
    private val client = mockk<SimpleTelegramClient>()
    private val backups = mockk<EditBackupStore>()
    private val requests = mutableListOf<TdApi.Function<*>>()
    private val service = TdLibClientService(
        client, RateLimiter.ofDefaults("photo-edit"), CircuitBreaker.ofDefaults("photo-edit"), SimpleMeterRegistry(),
        editBackupStore = backups,
    )

    private fun original(): TdApi.Message = TdApi.Message().apply {
        id = 99; chatId = 42
        content = TdApi.MessagePhoto().apply {
            caption = TdApi.FormattedText("Original caption", emptyArray())
            showCaptionAboveMedia = true
            hasSpoiler = true
        }
    }

    private fun respond(canEditMedia: Boolean = true) {
        every { backups.save(any(), any()) } returns Path.of("backup.tdapi")
        every { client.send(any<TdApi.Function<TdApi.Object>>(), any<GenericResultHandler<TdApi.Object>>()) } answers {
            val request = firstArg<TdApi.Function<TdApi.Object>>()
            requests += request
            val result: TdApi.Object = when (request) {
                is TdApi.GetMessages -> TdApi.Messages().apply { messages = arrayOf(original()) }
                is TdApi.GetMessageProperties -> TdApi.MessageProperties().apply {
                    canBeEdited = true; this.canEditMedia = canEditMedia
                }
                is TdApi.GetOption -> TdApi.OptionValueInteger(1024)
                is TdApi.EditMessageMedia, is TdApi.EditMessageCaption -> original()
                is TdApi.GetChat -> TdApi.Chat().apply { title = "Saved" }
                else -> error("Unexpected request: $request")
            }
            secondArg<GenericResultHandler<TdApi.Object>>().onResult(Result.of(result))
        }
    }

    @Test
    fun `photo replacement keeps omitted caption position and spoiler`() {
        respond()
        val result = service.editPhotoPost(42, 99, "C:\\ready\\photo.png", null, ParseMode.PLAIN)
        val input = assertIs<TdApi.InputMessagePhoto>(requests.filterIsInstance<TdApi.EditMessageMedia>().single().inputMessageContent)
        assertEquals("Original caption", input.caption.text)
        assertTrue(input.showCaptionAboveMedia)
        assertTrue(input.hasSpoiler)
        assertEquals(99, result.messageId)
        assertTrue(requests.none { it is TdApi.SendMessage || it is TdApi.DeleteMessages })
    }

    @Test
    fun `caption-only edit uses EditMessageCaption and retains media`() {
        respond()
        service.editPhotoPost(42, 99, null, "New caption", ParseMode.PLAIN)
        assertEquals("New caption", requests.filterIsInstance<TdApi.EditMessageCaption>().single().caption.text)
        assertTrue(requests.none { it is TdApi.EditMessageMedia })
    }

    @Test
    fun `media replacement is blocked before mutation when Telegram denies it`() {
        respond(canEditMedia = false)
        assertThrows<InvalidToolInputException> {
            service.editPhotoPost(42, 99, "C:\\ready\\photo.png", null, ParseMode.PLAIN)
        }
        assertTrue(requests.none { it is TdApi.EditMessageMedia || it is TdApi.EditMessageCaption })
    }
}
