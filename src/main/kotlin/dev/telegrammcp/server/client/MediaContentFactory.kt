package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.MediaUpload
import it.tdlight.jni.TdApi
import java.nio.file.Path

internal object MediaContentFactory {
    fun mediaType(upload: MediaUpload): String {
        if (upload.mediaType != "auto") return upload.mediaType
        return when (Path.of(upload.filePath).fileName.toString().substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg", "png" -> "photo"
            "mp4", "mov", "mkv" -> "video"
            "mp3", "m4a", "flac", "wav", "ogg" -> "audio"
            else -> "document"
        }
    }

    fun build(upload: MediaUpload, caption: TdApi.FormattedText): TdApi.InputMessageContent {
        val file = TdApi.InputFileLocal(upload.filePath)
        return when (mediaType(upload)) {
            "photo" -> TdApi.InputMessagePhoto().apply {
                photo = TdApi.InputPhoto().apply { photo = file; addedStickerFileIds = intArrayOf() }
                this.caption = caption
            }
            "video" -> TdApi.InputMessageVideo().apply {
                video = TdApi.InputVideo().apply { video = file; addedStickerFileIds = intArrayOf(); supportsStreaming = true }
                this.caption = caption
            }
            "audio" -> TdApi.InputMessageAudio().apply {
                audio = TdApi.InputAudio().apply { audio = file; title = ""; performer = "" }
                this.caption = caption
            }
            "document" -> TdApi.InputMessageDocument().apply {
                document = TdApi.InputDocument(file, null, false)
                this.caption = caption
            }
            else -> throw InvalidToolInputException("media_type must be auto, photo, document, video or audio")
        }
    }

    fun validateAlbum(uploads: List<MediaUpload>) {
        if (uploads.size !in 2..10) throw InvalidToolInputException("An album needs 2 to 10 files")
        val types = uploads.map(::mediaType).toSet()
        if (!(types.all { it in setOf("photo", "video") } || types == setOf("document") || types == setOf("audio"))) {
            throw InvalidToolInputException("An album can mix photos and videos, or contain only documents, or only audio")
        }
    }
}
