package dev.telegrammcp.server.client

import it.tdlight.client.TelegramError
import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChannelActivityReaderTest {
    @Test
    fun `loads both chat lists deduplicates IDs and returns only joined channels`() {
        val requests = mutableListOf<Class<*>>()
        val reader = ChannelActivityReader { request ->
            requests += request.javaClass
            when (request) {
                is TdApi.LoadChats -> TdApi.Error(404, "END_OF_CHAT_LIST")
                is TdApi.GetChats -> TdApi.Chats(3, when (request.chatList) {
                    is TdApi.ChatListMain -> longArrayOf(30, 10)
                    else -> longArrayOf(10, 20)
                })
                is TdApi.GetChat -> when (request.chatId) {
                    10L -> TdApi.Chat().apply { id = 10; title = "Joined channel"; type = TdApi.ChatTypeSupergroup(100, true) }
                    20L -> TdApi.Chat().apply { id = 20; title = "Left channel"; type = TdApi.ChatTypeSupergroup(200, true) }
                    else -> TdApi.Chat().apply { id = 30; title = "Group"; type = TdApi.ChatTypeSupergroup(300, false) }
                }
                is TdApi.GetSupergroup -> TdApi.Supergroup().apply {
                    status = if (request.supergroupId == 100L) TdApi.ChatMemberStatusCreator().apply { isMember = true }
                    else TdApi.ChatMemberStatusLeft()
                    memberCount = 42
                }
                else -> error("Unexpected request ${request.javaClass.simpleName}")
            }
        }

        val page = reader.read(0, 10)

        assertEquals(3, page.totalChats)
        assertEquals(3, page.scannedChats)
        assertNull(page.nextOffset)
        assertEquals(listOf(10L), page.channels.map { it.chatId })
        assertEquals("Joined channel", page.channels.single().title)
        assertEquals(42, page.channels.single().memberCount)
        assertTrue(page.channels.single().archived)
        assertEquals(2, requests.count { it == TdApi.LoadChats::class.java })
        assertTrue(requests.none { it == TdApi.GetChatHistory::class.java })
        assertTrue(requests.none { it == TdApi.ViewMessages::class.java })
    }

    @Test
    fun `incomplete loading and nonterminal Telegram errors never return a partial inventory`() {
        val exhausted = ChannelActivityReader { request ->
            when (request) {
                is TdApi.LoadChats -> TdApi.Ok()
                else -> error("Unexpected request")
            }
        }
        assertFailsWith<IllegalStateException> { exhausted.read(0, 10) }

        val failed = ChannelActivityReader { request ->
            if (request is TdApi.LoadChats) throw TelegramError(TdApi.Error(500, "SERVER_ERROR"))
            error("Unexpected request")
        }
        assertFailsWith<TelegramError> { failed.read(0, 10) }
    }
}
