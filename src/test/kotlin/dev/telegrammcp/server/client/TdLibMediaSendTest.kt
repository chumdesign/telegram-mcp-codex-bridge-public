package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.MediaSendOptions
import dev.telegrammcp.server.model.MediaUpload
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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TdLibMediaSendTest {
    private val client = mockk<SimpleTelegramClient>()
    private val requests = mutableListOf<TdApi.Function<*>>()
    private val service = TdLibClientService(client, RateLimiter.ofDefaults("media-test"),
        CircuitBreaker.ofDefaults("media-test"), SimpleMeterRegistry())

    private fun message(id: Long, topic: Int? = 991, reply: Long? = null) = TdApi.Message().apply {
        this.id = id
        chatId = 42L
        topicId = topic?.let { TdApi.MessageTopicForum(it) }
        content = TdApi.MessageText().apply { text = TdApi.FormattedText("Sent", emptyArray()) }
        replyTo = reply?.let { TdApi.MessageReplyToMessage().apply { messageId = it; chatId = 42L } }
    }

    private fun respond() {
        every { client.send(any<TdApi.Function<TdApi.Object>>(), any<GenericResultHandler<TdApi.Object>>()) } answers {
            val request = firstArg<TdApi.Function<TdApi.Object>>()
            requests += request
            val response: TdApi.Object = when (request) {
                is TdApi.GetMessage -> message(55)
                is TdApi.SendMessage -> message(101, reply = 55)
                is TdApi.SendMessageAlbum -> TdApi.Messages().apply {
                    totalCount = 2
                    messages = arrayOf(message(101), message(102))
                }
                is TdApi.GetChat -> TdApi.Chat().apply { title = "Test" }
                else -> error("Unexpected request: $request")
            }
            secondArg<GenericResultHandler<TdApi.Object>>().onResult(Result.of(response))
        }
    }

    @Test fun `photo reply uses validated forum topic and native photo`() {
        respond()
        val sent = service.sendMediaFile(42L, MediaUpload("C:/posts/photo.jpg", "Caption"),
            MediaSendOptions(991L, 55L))
        val request = requests.filterIsInstance<TdApi.SendMessage>().single()
        assertIs<TdApi.InputMessagePhoto>(request.inputMessageContent)
        assertEquals(991, (request.topicId as TdApi.MessageTopicForum).forumTopicId)
        assertEquals(55L, (request.replyTo as TdApi.InputMessageReplyToMessage).messageId)
        assertEquals(55L, sent.replyToMessageId)
        assertTrue(requests.none { it is TdApi.DeleteMessages })
    }

    @Test fun `mismatched reply topic is rejected before uploading`() {
        respond()
        assertThrows<InvalidToolInputException> {
            service.sendMediaFile(42L, MediaUpload("C:/posts/photo.jpg"), MediaSendOptions(3L, 55L))
        }
        assertTrue(requests.none { it is TdApi.SendMessage })
    }

    @Test fun `album sends one native request with scheduled topic`() {
        respond()
        val sent = service.sendMediaAlbum(42L,
            listOf(MediaUpload("C:/posts/a.jpg"), MediaUpload("C:/posts/b.mp4")),
            MediaSendOptions(messageThreadId = 991L, sendAtEpochSeconds = 1_800_000_000))
        val request = requests.filterIsInstance<TdApi.SendMessageAlbum>().single()
        assertEquals(991, (request.topicId as TdApi.MessageTopicForum).forumTopicId)
        assertEquals(2, request.inputMessageContents.size)
        assertIs<TdApi.InputMessagePhoto>(request.inputMessageContents[0])
        assertIs<TdApi.InputMessageVideo>(request.inputMessageContents[1])
        assertIs<TdApi.MessageSchedulingStateSendAtDate>(request.options.schedulingState)
        assertEquals(2, sent.size)
    }
}
