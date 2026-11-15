package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.MediaUpload
import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MediaContentFactoryTest {
    private val caption = TdApi.FormattedText("Test", emptyArray())

    @Test fun `jpg and png are photos while pdf is a document`() {
        assertIs<TdApi.InputMessagePhoto>(MediaContentFactory.build(MediaUpload("C:/posts/a.jpg"), caption))
        assertIs<TdApi.InputMessagePhoto>(MediaContentFactory.build(MediaUpload("C:/posts/a.png"), caption))
        assertIs<TdApi.InputMessageDocument>(MediaContentFactory.build(MediaUpload("C:/posts/a.pdf"), caption))
        assertIs<TdApi.InputMessageDocument>(MediaContentFactory.build(MediaUpload("C:/posts/a.jpg", mediaType = "document"), caption))
    }

    @Test fun `mixed photo-video album is allowed but mixed document-photo is rejected`() {
        MediaContentFactory.validateAlbum(listOf(MediaUpload("a.jpg"), MediaUpload("b.mp4")))
        assertThrows<InvalidToolInputException> {
            MediaContentFactory.validateAlbum(listOf(MediaUpload("a.jpg"), MediaUpload("b.pdf")))
        }
        assertThrows<InvalidToolInputException> { MediaContentFactory.validateAlbum(listOf(MediaUpload("a.jpg"))) }
        assertEquals("video", MediaContentFactory.mediaType(MediaUpload("video.mp4")))
    }
}
