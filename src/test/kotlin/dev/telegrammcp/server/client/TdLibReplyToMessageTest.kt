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
import dev.telegrammcp.server.exception.TelegramApiException
import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TdLibReplyToMessageTest {
    private val client = mockk<SimpleTelegramClient>()
    private val requests = mutableListOf<TdApi.Function<*>>()
    private val service = TdLibClientService(
        client, RateLimiter.ofDefaults("reply-test"), CircuitBreaker.ofDefaults("reply-test"), SimpleMeterRegistry(),
    )

    private fun respond(handler: (TdApi.Function<*>) -> TdApi.Object) {
        every { client.send(any<TdApi.Function<TdApi.Object>>(), any<GenericResultHandler<TdApi.Object>>()) } answers {
            val request = firstArg<TdApi.Function<TdApi.Object>>()
            requests += request
            secondArg<GenericResultHandler<TdApi.Object>>().onResult(Result.of(handler(request)))
        }
    }

    private fun message(id: Long, topic: Int? = null, reply: Long? = null): TdApi.Message = TdApi.Message().apply {
        this.id = id
        chatId = 42L
        topicId = topic?.let { TdApi.MessageTopicForum(it) }
        content = TdApi.MessageText().apply { text = TdApi.FormattedText("Reply", emptyArray()) }
        replyTo = reply?.let { TdApi.MessageReplyToMessage().apply { messageId = it; chatId = 42L } }
    }

    private fun replies(target: TdApi.Message, outgoing: TdApi.Message = message(101, 991, 55)) {
        respond { request ->
            when (request) {
                is TdApi.GetMessage -> target.also {
                    assertEquals(42L, request.chatId)
                    assertEquals(55L, request.messageId)
                }
                is TdApi.SendMessage -> outgoing
                is TdApi.GetChat -> TdApi.Chat().apply { title = "Test" }
                else -> error("Unexpected request: $request")
            }
        }
    }

    @Test
    fun `reply request preserves the validated forum topic and target`() {
        replies(message(55, 991))

        val result = service.replyToMessage(42, 55, "Reply", ParseMode.PLAIN, 991)

        val request = requests.filterIsInstance<TdApi.SendMessage>().single()
        assertEquals(42L, request.chatId)
        assertEquals(991, (request.topicId as TdApi.MessageTopicForum).forumTopicId)
        assertEquals(55L, (request.replyTo as TdApi.InputMessageReplyToMessage).messageId)
        assertEquals(55L, result.replyToMessageId)
        assertEquals(991L, result.messageThreadId)
        assertFalse(requests.any { it is TdApi.ViewMessages || it is TdApi.DeleteMessages })
    }

    @Test
    fun `implicit topic follows the target while ordinary private replies need no topic`() {
        replies(message(55, 3), message(101, 3, 55))
        service.replyToMessage(42, 55, "Reply", ParseMode.PLAIN)
        assertEquals(3, (requests.filterIsInstance<TdApi.SendMessage>().single().topicId as TdApi.MessageTopicForum).forumTopicId)

        requests.clear()
        replies(message(55), message(101, reply = 55))
        service.replyToMessage(42, 55, "Reply", ParseMode.PLAIN)
        assertEquals(null, requests.filterIsInstance<TdApi.SendMessage>().single().topicId)
    }

    @Test
    fun `cross-topic or non-forum target is rejected before sending`() {
        for (topic in listOf(3, null)) {
            requests.clear()
            replies(message(55, topic))
            assertFailsWith<InvalidToolInputException> { service.replyToMessage(42, 55, "Reply", ParseMode.PLAIN, 991) }
            assertTrue(requests.none { it is TdApi.SendMessage })
        }
    }

    @Test
    fun `mismatched or missing target is rejected before sending`() {
        for (target in listOf(message(56, 991), message(55, 991).apply { chatId = 99L })) {
            replies(target)
            assertFailsWith<InvalidToolInputException> { service.replyToMessage(42, 55, "Reply", ParseMode.PLAIN, 991) }
        }
        respond { TdApi.Error(404, "MESSAGE_NOT_FOUND") }
        val missing = assertFailsWith<TelegramApiException> { service.replyToMessage(42, 55, "Reply", ParseMode.PLAIN, 991) }
        assertEquals(404, missing.errorCode)
        assertTrue(requests.none { it is TdApi.SendMessage })
    }

    @Test
    fun `unlinked result reports sent ID without deleting or resending`() {
        replies(message(55, 991), message(101, 991))

        val error = assertFailsWith<IllegalStateException> { service.replyToMessage(42, 55, "Reply", ParseMode.PLAIN, 991) }

        assertTrue(error.message.orEmpty().contains("Message 101 was sent"))
        assertEquals(1, requests.count { it is TdApi.SendMessage })
        assertTrue(requests.none { it is TdApi.DeleteMessages })
    }
}
