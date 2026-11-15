package dev.telegrammcp.server.model

/** Complete native Telegram Article, including its ordered content blocks. */
data class ArticleInfo(
    val chatId: Long,
    val messageId: Long,
    val isFull: Boolean,
    val blocks: List<ArticleBlockInfo>,
)

data class ArticleBlockInfo(
    val type: String,
    val text: String? = null,
    val richText: ArticleRichTextInfo? = null,
    val size: Int? = null,
    val rows: List<List<ArticleTableCellInfo>>? = null,
    val items: List<ArticleListItemInfo>? = null,
    val blocks: List<ArticleBlockInfo>? = null,
    val caption: ArticleRichTextInfo? = null,
    val credit: ArticleRichTextInfo? = null,
    val isBordered: Boolean? = null,
    val isStriped: Boolean? = null,
    val isCompact: Boolean? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fileSize: Long? = null,
    val fileId: Int? = null,
    val photoCaption: ArticleRichTextInfo? = null,
    val photoCredit: ArticleRichTextInfo? = null,
    val hasSpoiler: Boolean? = null,
)

data class ArticleRichTextInfo(
    val type: String,
    val text: String? = null,
    val url: String? = null,
    val children: List<ArticleRichTextInfo>? = null,
)

data class ArticleTableCellInfo(
    val text: String,
    val richText: ArticleRichTextInfo,
    val isHeader: Boolean,
    val colspan: Int,
    val rowspan: Int,
    val align: String,
    val valign: String,
)

data class ArticleListItemInfo(
    val blocks: List<ArticleBlockInfo>,
    val value: Int,
    val type: String,
    val hasCheckbox: Boolean,
    val isChecked: Boolean,
)
