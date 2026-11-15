package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.ArticleDraft
import dev.telegrammcp.server.model.ArticleLimits
import dev.telegrammcp.server.model.ArticleValidation
import it.tdlight.jni.TdApi
import java.net.URI

/** Builds only TDLib blocks: TDLib's Markdown/HTML sources are restricted to bots. */
internal object ArticleContentFactory {
    val DEFAULT_LIMITS = ArticleLimits(32_768, 500, 16, 50, 20)

    fun build(text: String, photoPath: String): TdApi.InputMessageRichMessage =
        build(ArticleDraft(text = text, photoPath = photoPath), DEFAULT_LIMITS).first

    fun build(draft: ArticleDraft, limits: ArticleLimits): Pair<TdApi.InputMessageRichMessage, ArticleValidation> {
        if (listOf(draft.text, draft.richMarkdown, draft.richHtml, draft.blocks).count { it != null } != 1) {
            throw InvalidToolInputException("Specify exactly one of text, rich_markdown, rich_html, or blocks")
        }
        val inputBlocks = when {
            draft.text != null -> {
                if (draft.text.isBlank()) throw InvalidToolInputException("article text must not be blank")
                draft.text.replace("\r\n", "\n").split(Regex("\n[ \t]*\n")).map {
                    mapOf("type" to "paragraph", "text" to it)
                }
            }
            draft.richMarkdown != null -> ArticleMarkupParser.markdown(draft.richMarkdown)
            draft.richHtml != null -> ArticleMarkupParser.html(draft.richHtml)
            else -> draft.blocks!!
        }
        if (inputBlocks.isEmpty()) throw InvalidToolInputException("article content must not be empty")
        val blocks = mutableListOf<TdApi.InputPageBlock>()
        draft.photoPath?.let { path -> blocks += photoBlock(path) }
        inputBlocks.forEachIndexed { i, value -> blocks += block(value, "blocks[$i]") }
        return fromBlocks(blocks, limits)
    }

    fun photoBlock(path: String): TdApi.InputPageBlockPhoto {
        val photo = TdApi.InputPhoto().apply {
            this.photo = TdApi.InputFileLocal(path)
            this.addedStickerFileIds = intArrayOf()
        }
        return TdApi.InputPageBlockPhoto(photo, null, false)
    }

    fun fromBlocks(blocks: List<TdApi.InputPageBlock>, limits: ArticleLimits): Pair<TdApi.InputMessageRichMessage, ArticleValidation> {
        if (blocks.isEmpty()) throw InvalidToolInputException("Article content must not be empty")
        val stats = Stats()
        blocks.forEach { countBlock(it, 1, stats, limits) }
        if (stats.text > limits.maxTextLength) throw InvalidToolInputException(
            "Article text has ${stats.text} characters; Telegram limit is ${limits.maxTextLength}",
        )
        if (stats.blocks > limits.maxBlockCount) throw InvalidToolInputException(
            "Article has ${stats.blocks} blocks/list items/table rows; Telegram limit is ${limits.maxBlockCount}",
        )
        if (stats.depth > limits.maxDepth) throw InvalidToolInputException(
            "Article nesting depth ${stats.depth} exceeds Telegram limit ${limits.maxDepth}",
        )
        if (stats.media > limits.maxMediaCount) throw InvalidToolInputException(
            "Article has ${stats.media} media; Telegram limit is ${limits.maxMediaCount}",
        )
        val content = TdApi.InputMessageRichMessage(
            TdApi.InputRichMessage(TdApi.RichMessageSourceBlocks(blocks.toTypedArray()), false, false), false,
        )
        return content to ArticleValidation(true, stats.text, stats.blocks, stats.depth, stats.media, stats.tables, limits)
    }

    private fun block(value: Map<String, Any?>, path: String): TdApi.InputPageBlock {
        val allowed = when (value["type"]) {
            "paragraph" -> setOf("type", "text")
            "heading" -> setOf("type", "size", "text")
            "divider" -> setOf("type")
            "quote" -> setOf("type", "blocks", "credit")
            "list" -> setOf("type", "ordered", "items")
            "table" -> setOf("type", "caption", "rows", "bordered", "striped", "compact")
            else -> throw InvalidToolInputException("Unsupported Article block type '${value["type"]}' at $path")
        }
        rejectExtra(value.keys, allowed, path)
        return when (val type = value["type"]) {
        "paragraph" -> TdApi.InputPageBlockParagraph(rich(value["text"], "$path.text"))
        "heading" -> {
            val size = integer(value["size"] ?: 1, "$path.size")
            if (size !in 1..6) throw InvalidToolInputException("$path.size must be 1..6")
            TdApi.InputPageBlockSectionHeading(rich(value["text"], "$path.text"), size)
        }
        "divider" -> TdApi.InputPageBlockDivider()
        "quote" -> TdApi.InputPageBlockBlockQuote(
            nestedBlocks(value["blocks"], "$path.blocks").toTypedArray(), rich(value["credit"] ?: "", "$path.credit"),
        )
        "list" -> {
            val items = value["items"] as? List<*> ?: throw InvalidToolInputException("$path.items must be an array")
            if (items.isEmpty()) throw InvalidToolInputException("$path.items must not be empty")
            val ordered = value["ordered"] == true
            TdApi.InputPageBlockList(items.mapIndexed { i, item ->
                val itemBlocks = if (item is Map<*, *> && item["blocks"] != null) {
                    nestedBlocks(item["blocks"], "$path.items[$i].blocks")
                } else {
                    listOf(TdApi.InputPageBlockParagraph(rich(item, "$path.items[$i]")))
                }
                TdApi.InputPageBlockListItem(
                    itemBlocks.toTypedArray(), false, false, if (ordered) i + 1 else 0, if (ordered) "1" else "",
                )
            }.toTypedArray())
        }
        "table" -> {
            val rows = value["rows"] as? List<*> ?: throw InvalidToolInputException("$path.rows must be an array")
            if (rows.isEmpty()) throw InvalidToolInputException("$path.rows must not be empty")
            val cells = rows.mapIndexed { rowIndex, row ->
                val columns = row as? List<*> ?: throw InvalidToolInputException("$path.rows[$rowIndex] must be an array")
                if (columns.isEmpty()) throw InvalidToolInputException("$path.rows[$rowIndex] must not be empty")
                columns.mapIndexed { colIndex, cell ->
                    val c = cell as? Map<*, *> ?: mapOf("text" to cell)
                    rejectExtra(c.keys, setOf("text", "header", "colspan", "rowspan", "align", "valign"),
                        "$path.rows[$rowIndex][$colIndex]")
                    val colspan = integer(c["colspan"] ?: 1, "$path.rows[$rowIndex][$colIndex].colspan")
                    val rowspan = integer(c["rowspan"] ?: 1, "$path.rows[$rowIndex][$colIndex].rowspan")
                    if (colspan < 1 || rowspan < 1) throw InvalidToolInputException("Table cell spans must be positive")
                    val align = when (c["align"] ?: "left") {
                        "left" -> TdApi.PageBlockHorizontalAlignmentLeft()
                        "center" -> TdApi.PageBlockHorizontalAlignmentCenter()
                        "right" -> TdApi.PageBlockHorizontalAlignmentRight()
                        else -> throw InvalidToolInputException("Invalid table cell align")
                    }
                    val valign = when (c["valign"] ?: "top") {
                        "top" -> TdApi.PageBlockVerticalAlignmentTop()
                        "middle" -> TdApi.PageBlockVerticalAlignmentMiddle()
                        "bottom" -> TdApi.PageBlockVerticalAlignmentBottom()
                        else -> throw InvalidToolInputException("Invalid table cell valign")
                    }
                    TdApi.PageBlockTableCell(
                        rich(c["text"], "$path.rows[$rowIndex][$colIndex].text"), c["header"] == true,
                        colspan, rowspan, align, valign,
                    )
                }.toTypedArray()
            }.toTypedArray()
            TdApi.InputPageBlockTable(
                rich(value["caption"] ?: "", "$path.caption"), cells, value["bordered"] == true, value["striped"] == true, value["compact"] == true,
            )
        }
        else -> throw InvalidToolInputException("Unsupported Article block type '$type' at $path")
        }
    }

    private fun nestedBlocks(raw: Any?, path: String): List<TdApi.InputPageBlock> {
        val entries = raw as? List<*> ?: throw InvalidToolInputException("$path must be an array")
        return entries.mapIndexed { i, item ->
            @Suppress("UNCHECKED_CAST")
            block(item as? Map<String, Any?> ?: throw InvalidToolInputException("$path[$i] must be an object"), "$path[$i]")
        }
    }

    private fun rich(value: Any?, path: String): TdApi.RichText = when (value) {
        is String -> TdApi.RichTextPlain(value)
        is List<*> -> TdApi.RichTexts(value.mapIndexed { i, v -> rich(v, "$path[$i]") }.toTypedArray())
        is Map<*, *> -> {
            val child = value["children"] ?: value["text"] ?: ""
            rejectExtra(value.keys, when (value["type"]) {
                "text", "plain" -> setOf("type", "text")
                "bold", "italic" -> setOf("type", "text", "children")
                "link" -> setOf("type", "url", "text", "children")
                else -> throw InvalidToolInputException("Unsupported inline formatting '${value["type"]}' at $path")
            }, path)
            when (value["type"]) {
                "text", "plain" -> TdApi.RichTextPlain(value["text"] as? String
                    ?: throw InvalidToolInputException("$path.text must be a string"))
                "bold" -> TdApi.RichTextBold(rich(child, "$path.children"))
                "italic" -> TdApi.RichTextItalic(rich(child, "$path.children"))
                "link" -> TdApi.RichTextUrl(rich(child, "$path.children"), validUrl(value["url"], path), false)
                else -> throw InvalidToolInputException("Unsupported inline formatting '${value["type"]}' at $path")
            }
        }
        else -> throw InvalidToolInputException("$path must be text or an array of rich text nodes")
    }

    private fun validUrl(raw: Any?, path: String): String {
        val url = raw as? String ?: throw InvalidToolInputException("$path.url is required")
        val parsed = runCatching { URI(url) }.getOrNull()
        if (parsed?.scheme?.lowercase() !in setOf("https", "http") || parsed?.host.isNullOrBlank()) {
            throw InvalidToolInputException("$path.url must be an absolute HTTP(S) URL")
        }
        return url
    }

    private fun rejectExtra(keys: Set<*>, allowed: Set<String>, path: String) {
        val extra = keys.filter { it !in allowed }
        if (extra.isNotEmpty()) throw InvalidToolInputException("Unsupported field(s) ${extra.joinToString()} at $path")
    }

    private fun integer(raw: Any?, path: String): Int = when (raw) {
        is Number -> raw.toInt().takeIf { it.toDouble() == raw.toDouble() }
        else -> null
    } ?: throw InvalidToolInputException("$path must be an integer")

    private class Stats(var text: Int = 0, var blocks: Int = 0, var depth: Int = 0, var media: Int = 0, var tables: Int = 0)

    private fun countBlock(block: TdApi.InputPageBlock, depth: Int, stats: Stats, limits: ArticleLimits) {
        stats.blocks++
        stats.depth = maxOf(stats.depth, depth)
        when (block) {
            is TdApi.InputPageBlockPhoto -> stats.media++
            is TdApi.InputPageBlockDivider -> Unit
            is TdApi.InputPageBlockParagraph -> countRich(block.text, depth + 1, stats)
            is TdApi.InputPageBlockSectionHeading -> countRich(block.text, depth + 1, stats)
            is TdApi.InputPageBlockBlockQuote -> {
                countRich(block.credit, depth + 1, stats)
                block.blocks.orEmpty().forEach { countBlock(it, depth + 1, stats, limits) }
            }
            is TdApi.InputPageBlockList -> block.items.orEmpty().forEach { item ->
                stats.blocks++
                item.blocks.orEmpty().forEach { countBlock(it, depth + 1, stats, limits) }
            }
            is TdApi.InputPageBlockTable -> {
                stats.tables++
                countRich(block.caption, depth + 1, stats)
                block.cells.orEmpty().forEach { row ->
                    stats.blocks++
                    val columns = row.sumOf { it.colspan.coerceAtLeast(1) }
                    if (columns > limits.maxTableColumns) throw InvalidToolInputException(
                        "Table has $columns columns; Telegram limit is ${limits.maxTableColumns}",
                    )
                    row.forEach { countRich(it.text, depth + 1, stats) }
                }
            }
            else -> throw InvalidToolInputException("Unsupported Article block in validation")
        }
    }

    private fun countRich(value: TdApi.RichText?, depth: Int, stats: Stats) {
        stats.depth = maxOf(stats.depth, depth)
        when (value) {
            is TdApi.RichTextPlain -> stats.text += value.text.codePointCount(0, value.text.length)
            is TdApi.RichTexts -> value.texts.orEmpty().forEach { countRich(it, depth + 1, stats) }
            is TdApi.RichTextBold -> countRich(value.text, depth + 1, stats)
            is TdApi.RichTextItalic -> countRich(value.text, depth + 1, stats)
            is TdApi.RichTextUrl -> countRich(value.text, depth + 1, stats)
            is TdApi.RichTextUnderline -> countRich(value.text, depth + 1, stats)
            is TdApi.RichTextStrikethrough -> countRich(value.text, depth + 1, stats)
            is TdApi.RichTextFixed -> countRich(value.text, depth + 1, stats)
            is TdApi.RichTextSpoiler -> countRich(value.text, depth + 1, stats)
            is TdApi.RichTextMarked -> countRich(value.text, depth + 1, stats)
            else -> throw InvalidToolInputException("Unsupported Article inline format in validation")
        }
    }
}
