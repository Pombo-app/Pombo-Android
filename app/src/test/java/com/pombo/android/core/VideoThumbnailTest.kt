package com.pombo.android.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoThumbnailTest {

    @Test
    fun `only plain bounded base64 is accepted`() {
        assertEquals("/9j/4AAQSkZJRg==", VideoThumbnail.accept("/9j/4AAQSkZJRg=="))
        assertNull(VideoThumbnail.accept("\" onerror=\"x"))
        assertNull(VideoThumbnail.accept("A".repeat(VideoThumbnail.MAX_B64 + 1)))
        assertNull(VideoThumbnail.accept(""))
        assertNull(VideoThumbnail.accept(null))
    }

    @Test
    fun `an announce's thumbnail reaches the file metadata, a bad one does not`() {
        fun md(thumb: String) = JSONObject().put("transferId", "t").put("originalSize", 10).put("thumbnail", thumb)
        assertEquals("QUJD", StorageMedia.StorageFileMetadata.from(md("QUJD"))?.thumbnail)
        assertNull(StorageMedia.StorageFileMetadata.from(md("<img>"))?.thumbnail)
    }
}
