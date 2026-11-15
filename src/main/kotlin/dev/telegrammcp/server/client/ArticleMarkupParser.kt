package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.InvalidToolInputException
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/** Deliberately strict subset of rich Markdown/HTML; unhandled syntax fails preflight. */
internal object ArticleMarkupParser {
    private val markdownParser = Parser.builder().extensions(listOf(TablesExtension.create())).build()

    fun markdown(source: String): List<Map<String, Any?>> {
        if (source.isBlank()) throw InvalidToolInputException("rich_markdown must not be blank")
        return children(markdownParser.parse(source)).map(::markdownBlock)
    }

    private fun markdownBlock(node: Node): Map<String, Any?> = when (node) {
        is Heading -> mapOf("type" to "heading", "size" to node.level, "text" to markdownInlineChildren(node))
        is Paragraph -> mapOf("type" to "paragraph", "text" to markdownInlineChildren(node))
        is ThematicBreak -> mapOf("type" to "divider")
        is BlockQuote -> mapOf("type" to "quote", "blocks" to children(node).map(::markdownBlock))
        is BulletList -> listBlock(node, false)
        is OrderedList -> listBlock(node, true)
        is TableBlock -> {
            val rows = children(node).flatMap { section ->
                if (section !is TableHead && section !is TableBody) fail(section)
                children(section).map { row ->
                    if (row !is TableRow) fail(row)
                    children(row).map { cell ->
                        if (cell !is TableCell) fail(cell)
                        val align = when (cell.alignment?.name) {
                            "CENTER" -> "center"
                            "RIGHT" -> "right"
                            else -> "left"
                        }
                        mapOf("text" to markdownInlineChildren(cell), "header" to cell.isHeader, "align" to align)
                    }
                }
            }
            mapOf("type" to "table", "rows" to rows)
        }
        else -> fail(node)
    }

    private fun listBlock(node: Node, ordered: Boolean): Map<String, Any?> = mapOf(
        "type" to "list", "ordered" to ordered,
        "items" to children(node).map { item ->
            if (item !is ListItem) fail(item)
            mapOf("blocks" to children(item).map(::markdownBlock))
        },
    )

    private fun markdownInlineChildren(parent: Node): List<Any> = children(parent).map(::markdownInline)

    private fun markdownInline(node: Node): Any = when (node) {
        is Text -> node.literal
        is Code -> fail(node)
        is SoftLineBreak, is HardLineBreak -> "\n"
        is StrongEmphasis -> mapOf("type" to "bold", "children" to markdownInlineChildren(node))
        is Emphasis -> mapOf("type" to "italic", "children" to markdownInlineChildren(node))
        is Link -> mapOf("type" to "link", "url" to node.destination, "children" to markdownInlineChildren(node))
        else -> fail(node)
    }

    private fun children(node: Node): List<Node> = generateSequence(node.firstChild) { it.next }.toList()

    fun html(source: String): List<Map<String, Any?>> {
        if (source.isBlank()) throw InvalidToolInputException("rich_html must not be blank")
        // Jsoup parses inert markup only. Every resulting tag is then allowlisted below.
        val body = Jsoup.parseBodyFragment(source).body()
        val result = mutableListOf<Map<String, Any?>>()
        val inline = mutableListOf<Any>()
        fun flush() {
            if (inline.isEmpty()) return
            val content = inline.toMutableList()
            if (content.first() is String) content[0] = (content.first() as String).trimStart()
            if (content.last() is String) content[content.lastIndex] = (content.last() as String).trimEnd()
            if (content.any { it !is String || it.isNotBlank() }) {
                result += mapOf("type" to "paragraph", "text" to content.filter { it !is String || it.isNotEmpty() })
            }
            inline.clear()
        }
        val paragraphBreak = Regex("\\r?\\n[ \\t]*\\r?\\n")
        body.childNodes().forEach { node ->
            when (node) {
                is TextNode -> node.wholeText.split(paragraphBreak).forEachIndexed { index, part ->
                    if (index > 0) flush()
                    if (part.isNotEmpty()) inline += part
                }
                is Element -> if (node.normalName() in setOf("b", "strong", "i", "em", "a", "br")) {
                    inline += htmlInlineNode(node)
                } else {
                    flush()
                    result += htmlBlock(node)
                }
                else -> throw InvalidToolInputException("Unsupported HTML node: ${node.nodeName()}")
            }
        }
        flush()
        return result
    }

    private fun htmlBlock(e: Element): Map<String, Any?> {
        rejectUnsafeAttributes(e)
        return when (e.normalName()) {
            "p" -> mapOf("type" to "paragraph", "text" to htmlInlineChildren(e))
            in setOf("h1", "h2", "h3", "h4", "h5", "h6") -> mapOf(
                "type" to "heading", "size" to e.normalName().last().digitToInt(), "text" to htmlInlineChildren(e),
            )
            "hr" -> mapOf("type" to "divider")
            "blockquote" -> mapOf(
                "type" to "quote", "blocks" to listOf(mapOf("type" to "paragraph", "text" to htmlInlineChildren(e))),
            )
            "ul", "ol" -> mapOf(
                "type" to "list", "ordered" to (e.normalName() == "ol"),
                "items" to e.children().map { li ->
                    if (li.normalName() != "li") throw InvalidToolInputException("Only <li> is supported in lists")
                    rejectUnsafeAttributes(li)
                    mapOf("blocks" to listOf(mapOf("type" to "paragraph", "text" to htmlInlineChildren(li))))
                },
            )
            "table" -> htmlTable(e)
            else -> throw InvalidToolInputException("Unsupported rich HTML block <${e.normalName()}>")
        }
    }

    private fun htmlTable(table: Element): Map<String, Any?> {
        val rows = mutableListOf<List<Map<String, Any?>>>()
        var caption: List<Any>? = null
        fun row(tr: Element) {
            if (tr.normalName() != "tr") throw InvalidToolInputException("Only <tr> is supported in a table section")
            rejectUnsafeAttributes(tr)
            rows += tr.children().map { cell ->
                if (cell.normalName() !in setOf("td", "th")) {
                    throw InvalidToolInputException("Only <td>/<th> are supported in table rows")
                }
                rejectUnsafeAttributes(cell)
                mapOf(
                    "text" to htmlInlineChildren(cell),
                    "header" to (cell.normalName() == "th"),
                    "colspan" to htmlSpan(cell, "colspan"),
                    "rowspan" to htmlSpan(cell, "rowspan"),
                    "align" to (cell.attr("align").ifBlank { "left" }),
                    "valign" to (cell.attr("valign").ifBlank { "top" }),
                )
            }
        }
        table.children().forEach { child ->
            rejectUnsafeAttributes(child)
            when (child.normalName()) {
                "caption" -> caption = htmlInlineChildren(child)
                "tr" -> row(child)
                "thead", "tbody", "tfoot" -> child.children().forEach(::row)
                else -> throw InvalidToolInputException("Unsupported element <${child.normalName()}> in table")
            }
        }
        return mapOf(
            "type" to "table", "rows" to rows, "caption" to (caption ?: emptyList<Any>()),
            "bordered" to table.hasAttr("bordered"), "striped" to table.hasAttr("striped"),
        )
    }

    private fun htmlSpan(cell: Element, key: String): Int {
        val raw = cell.attr(key)
        if (raw.isBlank()) return 1
        return raw.toIntOrNull()?.takeIf { it > 0 }
            ?: throw InvalidToolInputException("<${cell.normalName()}> $key must be a positive integer")
    }

    private fun htmlInlineChildren(parent: Element): List<Any> = parent.childNodes().map(::htmlInlineNode)

    private fun htmlInlineNode(node: org.jsoup.nodes.Node): Any = when (node) {
        is TextNode -> node.wholeText
        is DataNode -> throw InvalidToolInputException("Script/style data are not supported in rich HTML")
        is Element -> {
            rejectUnsafeAttributes(node)
            when (node.normalName()) {
                "b", "strong" -> mapOf("type" to "bold", "children" to htmlInlineChildren(node))
                "i", "em" -> mapOf("type" to "italic", "children" to htmlInlineChildren(node))
                "a" -> mapOf("type" to "link", "url" to node.attr("href"), "children" to htmlInlineChildren(node))
                "br" -> "\n"
                else -> throw InvalidToolInputException("Unsupported rich HTML inline <${node.normalName()}>")
            }
        }
        else -> throw InvalidToolInputException("Unsupported HTML node: ${node.nodeName()}")
    }

    private fun rejectUnsafeAttributes(e: Element) {
        val allowed = when (e.normalName()) {
            "a" -> setOf("href")
            "table" -> setOf("bordered", "striped")
            "td", "th" -> setOf("colspan", "rowspan", "align", "valign")
            else -> emptySet()
        }
        e.attributes().forEach { attr ->
            val key = attr.key.lowercase()
            if (key.startsWith("on") || key !in allowed) {
                throw InvalidToolInputException("Unsupported or unsafe HTML attribute '$key'")
            }
        }
    }

    private fun fail(node: Node): Nothing =
        throw InvalidToolInputException("Unsupported rich Markdown node ${node.javaClass.simpleName}; use blocks for exact structure")
}
