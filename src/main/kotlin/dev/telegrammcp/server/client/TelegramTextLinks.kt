package dev.telegrammcp.server.client

import dev.telegrammcp.server.model.TelegramTextLink
import it.tdlight.jni.TdApi

/** Preserve TDLib link entities instead of returning only their visible text. */
internal fun extractTelegramTextLinks(content: TdApi.MessageContent?): List<TelegramTextLink> {
    val formatted = when (content) {
        is TdApi.MessageText -> content.text
        is TdApi.MessagePhoto -> content.caption
        is TdApi.MessageVideo -> content.caption
        is TdApi.MessageDocument -> content.caption
        is TdApi.MessageAudio -> content.caption
        is TdApi.MessageVoiceNote -> content.caption
        is TdApi.MessageAnimation -> content.caption
        else -> null
    } ?: return emptyList()
    val text = formatted.text ?: return emptyList()

    return formatted.entities.orEmpty().mapNotNull { entity ->
        val offset = entity.offset
        val length = entity.length
        if (offset < 0 || length <= 0 || offset > text.length || length > text.length - offset) {
            return@mapNotNull null
        }
        val visibleText = text.substring(offset, offset + length)
        val url = when (val type = entity.type) {
            is TdApi.TextEntityTypeTextUrl -> type.url
            is TdApi.TextEntityTypeUrl -> visibleText
            else -> null
        }
        url?.takeIf(String::isNotBlank)?.let { TelegramTextLink(visibleText, it, offset, length) }
    }
}
