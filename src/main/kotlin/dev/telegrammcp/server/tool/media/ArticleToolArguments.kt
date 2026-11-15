package dev.telegrammcp.server.tool.media

import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.ArticleDraft
import dev.telegrammcp.server.service.FileSecurityService

internal object ArticleToolArguments {
    private val extensions = setOf(".jpg", ".jpeg", ".png")
    private const val maxImageSize = 10L * 1024 * 1024

    fun draft(arguments: Map<String, Any>, files: FileSecurityService): ArticleDraft {
        fun optionalString(key: String): String? {
            val raw = arguments[key] ?: return null
            return raw as? String ?: throw InvalidToolInputException("$key must be a string")
        }
        val rawBlocks = arguments["blocks"]
        val blocks = if (rawBlocks == null) null else {
            val list = rawBlocks as? List<*> ?: throw InvalidToolInputException("blocks must be an array")
            list.mapIndexed { i, value ->
                @Suppress("UNCHECKED_CAST")
                value as? Map<String, Any?> ?: throw InvalidToolInputException("blocks[$i] must be an object")
            }
        }
        val photo = optionalString("photo_path")?.let {
            files.validateForUpload(it, extensions, maxImageSize).toString()
        }
        val removePhoto = when (val raw = arguments["remove_photo"]) {
            null -> false
            is Boolean -> raw
            else -> throw InvalidToolInputException("remove_photo must be a boolean")
        }
        if (removePhoto && photo != null) throw InvalidToolInputException("Choose photo_path or remove_photo, not both")
        return ArticleDraft(
            text = optionalString("text"), richMarkdown = optionalString("rich_markdown"),
            richHtml = optionalString("rich_html"), blocks = blocks, photoPath = photo, removePhoto = removePhoto,
        )
    }
}
