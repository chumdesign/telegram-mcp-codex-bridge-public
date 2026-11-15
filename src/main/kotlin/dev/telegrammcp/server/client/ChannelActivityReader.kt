package dev.telegrammcp.server.client

import dev.telegrammcp.server.model.ChannelActivity
import dev.telegrammcp.server.model.ChannelActivityPage
import it.tdlight.client.TelegramError
import it.tdlight.jni.TdApi
import java.util.concurrent.TimeUnit

/** Paginated metadata audit. Does not request history, open chats, or acknowledge messages. */
internal class ChannelActivityReader(private val send: (TdApi.Function<*>) -> TdApi.Object) {
    private inline fun <reified T : TdApi.Object> request(function: TdApi.Function<*>): T = send(function) as T

    fun read(offset: Int, limit: Int): ChannelActivityPage {
        require(offset in 0..20_000 && limit in 1..200) { "offset must be 0..20000 and limit 1..200" }
        val main = load(TdApi.ChatListMain())
        val archive = load(TdApi.ChatListArchive()).toSet()
        val ids = (main.toList() + archive).distinct().sorted()
        check(ids.size <= 20_000) { "Account chat inventory exceeds the 20000-chat bound" }
        val page = ids.drop(offset).take(limit)
        val channels = page.mapNotNull { id ->
            val chat = request<TdApi.Chat>(TdApi.GetChat(id))
            val type = chat.type as? TdApi.ChatTypeSupergroup ?: return@mapNotNull null
            if (!type.isChannel) return@mapNotNull null
            val group = request<TdApi.Supergroup>(TdApi.GetSupergroup(type.supergroupId))
            val joined = when (val status = group.status) {
                is TdApi.ChatMemberStatusCreator -> status.isMember
                is TdApi.ChatMemberStatusAdministrator, is TdApi.ChatMemberStatusMember -> true
                is TdApi.ChatMemberStatusRestricted -> status.isMember
                else -> false
            }
            if (!joined) return@mapNotNull null
            ChannelActivity(chat.id, chat.title, group.usernames?.activeUsernames?.firstOrNull(),
                id in archive, group.memberCount, group.status is TdApi.ChatMemberStatusCreator,
                group.status is TdApi.ChatMemberStatusAdministrator || group.status is TdApi.ChatMemberStatusCreator,
                chat.lastMessage?.date?.takeIf { it > 0 }, chat.lastMessage?.content?.javaClass?.simpleName)
        }
        val next = offset + page.size
        return ChannelActivityPage(ids.size, offset, page.size, next.takeIf { it < ids.size }, channels)
    }

    private fun load(list: TdApi.ChatList): LongArray {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        var complete = false
        for (batch in 0 until 45) {
            check(System.nanoTime() < deadline) { "Chat list loading timed out; inventory is incomplete" }
            when (val result = request<TdApi.Object>(TdApi.LoadChats(list, 500))) {
                is TdApi.Ok -> Unit
                is TdApi.Error -> {
                    if (result.code != 404) throw TelegramError(result)
                    complete = true
                    break
                }
                else -> error("Unexpected LoadChats response: ${result.javaClass.simpleName}")
            }
        }
        check(complete) { "Chat list did not finish loading; inventory is incomplete" }
        val ids = request<TdApi.Chats>(TdApi.GetChats(list, 20_001)).chatIds
        check(ids.size <= 20_000) { "Chat list exceeds the 20000-chat bound" }
        return ids
    }
}
