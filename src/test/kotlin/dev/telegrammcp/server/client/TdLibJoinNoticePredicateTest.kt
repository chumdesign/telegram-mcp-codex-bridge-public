package dev.telegrammcp.server.client

import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TdLibJoinNoticePredicateTest {
    private fun chat(content: TdApi.MessageContent, unreadCount: Int = 1): TdApi.Chat = TdApi.Chat().apply {
        this.unreadCount = unreadCount
        this.lastReadInboxMessageId = 10
        this.lastMessage = TdApi.Message().apply {
            id = 11
            this.content = content
        }
    }

    @Test
    fun `requires the registration service type and sole unread event`() {
        assertTrue(TdLibClientService.isSoleUnreadRegistrationNotice(chat(TdApi.MessageContactRegistered())))
        assertFalse(TdLibClientService.isSoleUnreadRegistrationNotice(chat(TdApi.MessageText(), 1)))
        assertFalse(TdLibClientService.isSoleUnreadRegistrationNotice(chat(TdApi.MessageContactRegistered(), 2)))
    }

    @Test
    fun `never selects manually unread or already read registration events`() {
        val manuallyUnread = chat(TdApi.MessageContactRegistered()).apply { isMarkedAsUnread = true }
        val alreadyRead = chat(TdApi.MessageContactRegistered()).apply { lastReadInboxMessageId = 11 }
        val outgoing = chat(TdApi.MessageContactRegistered()).apply { lastMessage.isOutgoing = true }
        assertFalse(TdLibClientService.isSoleUnreadRegistrationNotice(manuallyUnread))
        assertFalse(TdLibClientService.isSoleUnreadRegistrationNotice(alreadyRead))
        assertFalse(TdLibClientService.isSoleUnreadRegistrationNotice(outgoing))
    }
}
