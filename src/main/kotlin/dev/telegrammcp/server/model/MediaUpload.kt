package dev.telegrammcp.server.model

data class MediaUpload(
    val filePath: String,
    val caption: String? = null,
    val mediaType: String = "auto",
    val parseMode: ParseMode = ParseMode.PLAIN,
)

data class MediaSendOptions(
    val messageThreadId: Long? = null,
    val replyToMessageId: Long? = null,
    val sendAtEpochSeconds: Int? = null,
)
