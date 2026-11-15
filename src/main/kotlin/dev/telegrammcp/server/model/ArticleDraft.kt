package dev.telegrammcp.server.model

/** Exactly one content source is non-null. Blocks use the documented MCP JSON shape. */
data class ArticleDraft(
    val text: String? = null,
    val richMarkdown: String? = null,
    val richHtml: String? = null,
    val blocks: List<Map<String, Any?>>? = null,
    val photoPath: String? = null,
    /** Editing only: cover is preserved unless explicitly removed or replaced. */
    val removePhoto: Boolean = false,
)

/** Account-specific TDLib options, not the ordinary 4096-character message limit. */
data class ArticleLimits(
    val maxTextLength: Int,
    val maxBlockCount: Int,
    val maxDepth: Int,
    val maxMediaCount: Int,
    val maxTableColumns: Int,
)

data class ArticleValidation(
    val valid: Boolean,
    val textLength: Int,
    val blockCount: Int,
    val maxDepth: Int,
    val mediaCount: Int,
    val tableCount: Int,
    val limits: ArticleLimits,
)
