package dev.telegrammcp.server.tool.media

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.FileSecurityException
import dev.telegrammcp.server.model.TelegramMessage
import dev.telegrammcp.server.model.ArticleDraft
import dev.telegrammcp.server.model.ArticleValidation
import dev.telegrammcp.server.client.ArticleContentFactory
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.FileSecurityService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.server.McpSyncServerExchange
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SendArticleToolTest {
    private val chatId = -1001234567890L
    private val photo = "C:\\publish-ready\\meryl.png"
    private lateinit var client: TelegramClientService
    private lateinit var resolver: EntityResolverService
    private lateinit var files: FileSecurityService
    private lateinit var guard: OperationGuardService
    private lateinit var tool: SendArticleTool
    private lateinit var exchange: McpSyncServerExchange

    @BeforeEach
    fun setUp() {
        client = mockk()
        resolver = mockk()
        files = mockk()
        every { files.withStagedUploads<Any?>(any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            (args[1] as (List<Path>) -> Any?).invoke((args[0] as List<String>).map(Path::of))
        }
        guard = mockk(relaxed = true)
        exchange = mockk(relaxed = true)
        tool = SendArticleTool(
            client, resolver, mockk(relaxed = true), files, guard,
            mockk<AuditService>(relaxed = true), jacksonObjectMapper().findAndRegisterModules(),
            SimpleMeterRegistry(),
        )
        every { resolver.resolve(chatId as Any) } returns chatId
        every { files.validateForUpload(photo, setOf(".jpg", ".jpeg", ".png"), 10L * 1024 * 1024) } returns Path.of(photo)
        every { client.validateArticle(any()) } answers {
            ArticleValidation(true, 20, 2, 2, 1, 0, ArticleContentFactory.DEFAULT_LIMITS)
        }
        every { client.sendArticle(chatId, any<ArticleDraft>(), any()) } returns TelegramMessage(
            messageId = 123, chatId = chatId, chatTitle = "Український канал",
            senderName = "Український канал", text = "Article", date = Instant.now(), mediaType = "article",
        )
    }

    @Test
    fun `publishes one native article without calling send file`() {
        val result = tool.execute(exchange, mapOf("chat_id" to chatId, "text" to "Текст дослівно", "photo_path" to photo))
        assertFalse(result.isError)
        verify { guard.checkPermission("send_article", any()) }
        verify { client.sendArticle(chatId, ArticleDraft(text = "Текст дослівно", photoPath = Path.of(photo).toString()), null) }
        verify(exactly = 0) { client.sendFile(any(), any(), any()) }
    }

    @Test
    fun `schedules one article at the supplied future instant`() {
        val future = Instant.now().plusSeconds(7200).epochSecond
        val result = tool.execute(
            exchange,
            mapOf("chat_id" to chatId, "text" to "Текст дослівно", "photo_path" to photo, "send_at" to future),
        )
        assertFalse(result.isError)
        verify { client.sendArticle(chatId, ArticleDraft(text = "Текст дослівно", photoPath = Path.of(photo).toString()), future.toInt()) }
    }

    @Test
    fun `invalid photo path fails before Telegram call`() {
        every { files.validateForUpload(photo, any(), any()) } throws FileSecurityException("outside allowed roots")
        val result = tool.execute(exchange, mapOf("chat_id" to chatId, "text" to "Текст", "photo_path" to photo))
        assertTrue(result.isError)
        verify(exactly = 0) { client.sendArticle(any(), any<ArticleDraft>(), any()) }
    }
}
