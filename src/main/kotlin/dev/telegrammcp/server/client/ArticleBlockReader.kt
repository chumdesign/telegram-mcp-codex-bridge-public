package dev.telegrammcp.server.client

import dev.telegrammcp.server.model.*
import it.tdlight.jni.TdApi

/** Lossless for the block and inline types this connector can publish. */
internal object ArticleBlockReader {
    fun plainText(value: TdApi.PageBlock): String = plainBlock(block(value))
    private fun plainBlock(value: ArticleBlockInfo): String = value.text
        ?: value.rows?.joinToString("\n") { row -> row.joinToString("\t") { it.text } }
        ?: value.items?.joinToString("\n") { item -> item.blocks.joinToString("\n", transform = ::plainBlock) }
        ?: value.blocks?.joinToString("\n", transform = ::plainBlock)
        ?: ""
    fun block(value: TdApi.PageBlock): ArticleBlockInfo = when (value) {
        is TdApi.PageBlockParagraph -> textBlock("paragraph", value.text)
        is TdApi.PageBlockSectionHeading -> textBlock("heading", value.text, value.size)
        is TdApi.PageBlockDivider -> ArticleBlockInfo(type = "divider")
        is TdApi.PageBlockBlockQuote -> ArticleBlockInfo(
            type = "quote", blocks = value.blocks.orEmpty().map(::block), credit = rich(value.credit),
        )
        is TdApi.PageBlockList -> ArticleBlockInfo(
            type = "list",
            items = value.items.orEmpty().map {
                ArticleListItemInfo(it.blocks.orEmpty().map(::block), it.value, it.type, it.hasCheckbox, it.isChecked)
            },
        )
        is TdApi.PageBlockTable -> ArticleBlockInfo(
            type = "table", caption = rich(value.caption), isBordered = value.isBordered,
            isStriped = value.isStriped, isCompact = value.isCompact,
            rows = value.cells.orEmpty().map { row ->
                row.map { cell ->
                    val content = rich(cell.text)
                    ArticleTableCellInfo(
                        text = plain(content), richText = content, isHeader = cell.isHeader,
                        colspan = cell.colspan, rowspan = cell.rowspan,
                        align = cell.align.javaClass.simpleName.removePrefix("PageBlockHorizontalAlignment").lowercase(),
                        valign = cell.valign.javaClass.simpleName.removePrefix("PageBlockVerticalAlignment").lowercase(),
                    )
                }
            },
        )
        is TdApi.PageBlockPhoto -> {
            val photo = value.photo?.sizes?.maxByOrNull { it.width * it.height }
            ArticleBlockInfo(
                type = "photo", width = photo?.width, height = photo?.height, fileSize = photo?.photo?.size,
                fileId = photo?.photo?.id, photoCaption = value.caption?.text?.let(::rich),
                photoCredit = value.caption?.credit?.let(::rich), hasSpoiler = value.hasSpoiler,
            )
        }
        else -> ArticleBlockInfo(type = value.javaClass.simpleName)
    }

    private fun textBlock(type: String, value: TdApi.RichText, size: Int? = null): ArticleBlockInfo {
        val info = rich(value)
        return ArticleBlockInfo(type = type, text = plain(info), richText = info, size = size)
    }

    fun rich(value: TdApi.RichText?): ArticleRichTextInfo = when (value) {
        is TdApi.RichTextPlain -> ArticleRichTextInfo("text", text = value.text)
        is TdApi.RichTexts -> ArticleRichTextInfo("concat", children = value.texts.orEmpty().map(::rich))
        is TdApi.RichTextBold -> ArticleRichTextInfo("bold", children = listOf(rich(value.text)))
        is TdApi.RichTextItalic -> ArticleRichTextInfo("italic", children = listOf(rich(value.text)))
        is TdApi.RichTextUrl -> ArticleRichTextInfo("link", url = value.url, children = listOf(rich(value.text)))
        is TdApi.RichTextUnderline -> ArticleRichTextInfo("underline", children = listOf(rich(value.text)))
        is TdApi.RichTextStrikethrough -> ArticleRichTextInfo("strikethrough", children = listOf(rich(value.text)))
        is TdApi.RichTextFixed -> ArticleRichTextInfo("code", children = listOf(rich(value.text)))
        is TdApi.RichTextSpoiler -> ArticleRichTextInfo("spoiler", children = listOf(rich(value.text)))
        is TdApi.RichTextMarked -> ArticleRichTextInfo("mark", children = listOf(rich(value.text)))
        null -> ArticleRichTextInfo("text", text = "")
        else -> ArticleRichTextInfo(value.javaClass.simpleName)
    }

    fun plain(value: ArticleRichTextInfo): String = value.text ?: value.children.orEmpty().joinToString("") { plain(it) }
}
