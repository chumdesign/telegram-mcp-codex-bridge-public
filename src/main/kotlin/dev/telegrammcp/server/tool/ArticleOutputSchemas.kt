package dev.telegrammcp.server.tool

/** Recursive native Article schemas; reflection cannot expand recursive DTOs. */
internal object ArticleOutputSchemas {
    private val s = WireSchemas.string
    private val i = WireSchemas.integer
    private val b = WireSchemas.boolean
    private fun ref(name: String) = mapOf<String, Any>("\$ref" to "#/\$defs/$name")
    private fun array(value: Map<String, Any>) = WireSchemas.array(value)
    private fun nullable(value: Map<String, Any>) = WireSchemas.union(value, WireSchemas.nil)
    private fun shape(fields: Map<String, Map<String, Any>>, vararg required: String) = WireSchemas.obj(fields, required.toList())

    private val definitions: Map<String, Any> = mapOf(
        "articleRichText" to shape(mapOf(
            "type" to s, "text" to nullable(s), "url" to nullable(s),
            "children" to nullable(array(ref("articleRichText"))),
        ), "type"),
        "articleCell" to shape(mapOf(
            "text" to s, "richText" to ref("articleRichText"), "isHeader" to b,
            "colspan" to i, "rowspan" to i, "align" to s, "valign" to s,
        ), "text", "richText", "isHeader", "colspan", "rowspan", "align", "valign"),
        "articleItem" to shape(mapOf(
            "blocks" to array(ref("articleBlock")), "value" to i, "type" to s,
            "hasCheckbox" to b, "isChecked" to b,
        ), "blocks", "value", "type", "hasCheckbox", "isChecked"),
        "articleBlock" to shape(mapOf(
            "type" to s, "text" to nullable(s), "richText" to nullable(ref("articleRichText")), "size" to nullable(i),
            "rows" to nullable(array(array(ref("articleCell")))),
            "items" to nullable(array(ref("articleItem"))), "blocks" to nullable(array(ref("articleBlock"))),
            "caption" to nullable(ref("articleRichText")), "credit" to nullable(ref("articleRichText")),
            "isBordered" to nullable(b), "isStriped" to nullable(b), "isCompact" to nullable(b),
            "width" to nullable(i), "height" to nullable(i), "fileSize" to nullable(i), "fileId" to nullable(i),
            "photoCaption" to nullable(ref("articleRichText")), "photoCredit" to nullable(ref("articleRichText")),
            "hasSpoiler" to nullable(b),
        ), "type"),
    )

    val article: Map<String, Any> = shape(mapOf(
        "chatId" to i, "messageId" to i, "isFull" to b, "blocks" to array(ref("articleBlock")),
    ), "chatId", "messageId", "isFull", "blocks") + ("\$defs" to definitions)
}
