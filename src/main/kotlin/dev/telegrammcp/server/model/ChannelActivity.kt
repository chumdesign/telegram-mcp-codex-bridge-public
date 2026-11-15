package dev.telegrammcp.server.model

data class ChannelActivity(
    val chatId: Long,
    val title: String,
    val username: String?,
    val archived: Boolean,
    val memberCount: Int,
    val isOwner: Boolean,
    val isAdmin: Boolean,
    val lastMessageDate: Int?,
    val lastMessageKind: String?,
)

data class ChannelActivityPage(
    val totalChats: Int,
    val offset: Int,
    val scannedChats: Int,
    val nextOffset: Int?,
    val channels: List<ChannelActivity>,
    val scope: String = "main_and_archive",
    val chatListsFullyLoaded: Boolean = true,
)
