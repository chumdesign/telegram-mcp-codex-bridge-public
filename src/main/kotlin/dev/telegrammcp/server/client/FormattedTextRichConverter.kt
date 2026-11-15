package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.InvalidToolInputException
import it.tdlight.jni.TdApi

/** Preserves supported Telegram formatted-text entities when converting a text post to an Article. */
internal object FormattedTextRichConverter {
    fun rich(source: TdApi.FormattedText): TdApi.RichText {
        val text = source.text ?: ""
        val entities = source.entities.orEmpty()
        if (entities.isEmpty()) return TdApi.RichTextPlain(text)
        val boundaries = (listOf(0, text.length) + entities.flatMap { listOf(it.offset, it.offset + it.length) })
            .distinct().sorted()
        if (boundaries.first() < 0 || boundaries.last() > text.length || entities.any { it.length <= 0 }) {
            throw InvalidToolInputException("Invalid text entity offsets; no edit was made")
        }
        return TdApi.RichTexts(boundaries.zipWithNext().filter { (start, end) -> end > start }.map { (start, end) ->
            var segment: TdApi.RichText = TdApi.RichTextPlain(text.substring(start, end))
            val active = entities.filter { it.offset <= start && it.offset + it.length >= end }
                .sortedWith(compareByDescending<TdApi.TextEntity> { it.length }.thenBy { it.offset })
            active.asReversed().forEach { entity ->
                segment = when (val type = entity.type) {
                    is TdApi.TextEntityTypeBold -> TdApi.RichTextBold(segment)
                    is TdApi.TextEntityTypeItalic -> TdApi.RichTextItalic(segment)
                    is TdApi.TextEntityTypeUnderline -> TdApi.RichTextUnderline(segment)
                    is TdApi.TextEntityTypeStrikethrough -> TdApi.RichTextStrikethrough(segment)
                    is TdApi.TextEntityTypeSpoiler -> TdApi.RichTextSpoiler(segment)
                    is TdApi.TextEntityTypeCode -> TdApi.RichTextFixed(segment)
                    is TdApi.TextEntityTypeTextUrl -> TdApi.RichTextUrl(segment, type.url, false)
                    is TdApi.TextEntityTypeUrl -> TdApi.RichTextUrl(
                        segment, text.substring(entity.offset, entity.offset + entity.length), false,
                    )
                    else -> throw InvalidToolInputException(
                        "Text contains unsupported formatting ${type.javaClass.simpleName}; provide rich_html or blocks; no edit was made",
                    )
                }
            }
            segment
        }.toTypedArray())
    }

    fun paragraphs(source: TdApi.FormattedText): List<TdApi.InputPageBlock> {
        if (source.entities.orEmpty().isNotEmpty()) return listOf(TdApi.InputPageBlockParagraph(rich(source)))
        return source.text.replace("\r\n", "\n").split(Regex("\n[ \\t]*\n")).map {
            TdApi.InputPageBlockParagraph(TdApi.RichTextPlain(it))
        }
    }
}
