package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.InvalidToolInputException
import it.tdlight.jni.TdApi

/** Converts existing server blocks back to input blocks without dropping supported content. */
internal object ArticleBlockPreserver {
    fun blocks(source: Array<TdApi.PageBlock>?): List<TdApi.InputPageBlock> =
        source.orEmpty().map(::block)

    private fun block(value: TdApi.PageBlock): TdApi.InputPageBlock = when (value) {
        is TdApi.PageBlockParagraph -> TdApi.InputPageBlockParagraph(checked(value.text))
        is TdApi.PageBlockSectionHeading -> TdApi.InputPageBlockSectionHeading(checked(value.text), value.size)
        is TdApi.PageBlockDivider -> TdApi.InputPageBlockDivider()
        is TdApi.PageBlockBlockQuote -> TdApi.InputPageBlockBlockQuote(
            blocks(value.blocks).toTypedArray(), checked(value.credit),
        )
        is TdApi.PageBlockList -> TdApi.InputPageBlockList(value.items.orEmpty().map { item ->
            TdApi.InputPageBlockListItem(
                blocks(item.blocks).toTypedArray(), item.hasCheckbox, item.isChecked, item.value, item.type,
            )
        }.toTypedArray())
        is TdApi.PageBlockTable -> TdApi.InputPageBlockTable(
            checked(value.caption), value.cells.orEmpty().onEach { row ->
                row.forEach { checked(it.text) }
            }, value.isBordered, value.isStriped, value.isCompact,
        )
        is TdApi.PageBlockPhoto -> {
            if (!value.url.isNullOrBlank()) {
                throw InvalidToolInputException("Existing Article photo has an unsupported URL; no edit was made")
            }
            value.caption?.let { checked(it.text); checked(it.credit) }
            val size = value.photo?.sizes?.maxByOrNull { it.width.toLong() * it.height }
                ?: throw InvalidToolInputException("Existing Article photo has no reusable file; no edit was made")
            val id = size.photo?.id?.takeIf { it > 0 }
                ?: throw InvalidToolInputException("Existing Article photo has no reusable file ID; no edit was made")
            val photo = TdApi.InputPhoto().apply {
                this.photo = TdApi.InputFileId(id)
                addedStickerFileIds = intArrayOf()
                width = size.width
                height = size.height
            }
            TdApi.InputPageBlockPhoto(photo, value.caption, value.hasSpoiler)
        }
        else -> throw InvalidToolInputException(
            "Existing Article contains unsupported block ${value.javaClass.simpleName}; no edit was made",
        )
    }

    private fun checked(value: TdApi.RichText?): TdApi.RichText {
        return when (value) {
            is TdApi.RichTextPlain -> value
            is TdApi.RichTexts -> { value.texts.orEmpty().forEach(::checked); value }
            is TdApi.RichTextBold -> { checked(value.text); value }
            is TdApi.RichTextItalic -> { checked(value.text); value }
            is TdApi.RichTextUrl -> { checked(value.text); value }
            is TdApi.RichTextUnderline -> { checked(value.text); value }
            is TdApi.RichTextStrikethrough -> { checked(value.text); value }
            is TdApi.RichTextFixed -> { checked(value.text); value }
            is TdApi.RichTextSpoiler -> { checked(value.text); value }
            is TdApi.RichTextMarked -> { checked(value.text); value }
            null -> TdApi.RichTextPlain("")
            else -> throw InvalidToolInputException(
                "Existing Article contains unsupported inline ${value.javaClass.simpleName}; no edit was made",
            )
        }
    }
}
