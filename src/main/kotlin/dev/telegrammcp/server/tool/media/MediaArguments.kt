package dev.telegrammcp.server.tool.media

import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.MediaSendOptions
import dev.telegrammcp.server.model.MediaUpload
import dev.telegrammcp.server.service.FileSecurityService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.tool.ToolInputParsers
import java.time.Instant

internal object MediaArguments {
    fun positiveInteger(raw: Any, key: String): Long {
        val value = when (raw) {
            is Number -> raw.toString().toBigDecimalOrNull()?.let { runCatching { it.longValueExact() }.getOrNull() }
            is String -> raw.trim().toLongOrNull()
            else -> null
        }
        return value?.takeIf { it > 0 } ?: throw InvalidToolInputException("$key must be a positive integer")
    }

    fun options(args: Map<String, Any>): MediaSendOptions {
        val topic = args["message_thread_id"]?.let { positiveInteger(it, "message_thread_id") }
        if (topic != null && topic > Int.MAX_VALUE) throw InvalidToolInputException("Forum topic ID is out of range")
        val reply = args["reply_to_message_id"]?.let { positiveInteger(it, "reply_to_message_id") }
        val sendAt = args["send_at"]?.let { raw ->
            val seconds = if (raw is String && raw.trim().toLongOrNull() == null) {
                runCatching { Instant.parse(raw.trim()).epochSecond }.getOrNull()
                    ?: throw InvalidToolInputException("send_at must be ISO-8601 or Unix seconds")
            } else positiveInteger(raw, "send_at")
            if (seconds <= Instant.now().epochSecond || seconds > Int.MAX_VALUE) {
                throw InvalidToolInputException("send_at must be in the future and within TDLib's epoch range")
            }
            seconds.toInt()
        }
        return MediaSendOptions(topic, reply, sendAt)
    }

    fun upload(args: Map<String, Any>, security: FileSecurityService, guard: GuardrailService): MediaUpload {
        val path = args["file_path"] as? String ?: throw InvalidToolInputException("file_path is required")
        val type = (args["media_type"] as? String ?: "auto").lowercase()
        if (type !in setOf("auto", "photo", "document", "video", "audio")) {
            throw InvalidToolInputException("media_type must be auto, photo, document, video or audio")
        }
        val caption = args["caption"] as? String
        if (caption != null) {
            guard.validateInput(caption)
            // TDLib validates formatted caption limits. Bound raw input too; do not silently split or truncate.
            if (caption.length > 1024) throw InvalidToolInputException("caption exceeds 1024 characters; use send_article for long text with a photo")
        }
        val valid = if (type == "photo") security.validateForUpload(path, setOf(".jpg", ".jpeg", ".png"), 10L * 1024 * 1024)
            else security.validateForUpload(path)
        val upload = MediaUpload(valid.toString(), caption, type, ToolInputParsers.parseMode(args))
        if (dev.telegrammcp.server.client.MediaContentFactory.mediaType(upload) == "photo" && type == "auto") {
            security.validateForUpload(path, setOf(".jpg", ".jpeg", ".png"), 10L * 1024 * 1024)
        }
        return upload
    }
}
