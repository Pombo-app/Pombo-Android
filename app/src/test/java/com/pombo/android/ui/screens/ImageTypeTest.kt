package com.pombo.android.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageTypeTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun `recognises png gif and webp by their signature`() {
        assertEquals("image/png" to "png", imageType(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A)))
        assertEquals("image/gif" to "gif", imageType("GIF89a".toByteArray()))
        assertEquals("image/webp" to "webp", imageType("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()))
    }

    @Test
    fun `falls back to jpeg`() {
        assertEquals("image/jpeg" to "jpg", imageType(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals("image/jpeg" to "jpg", imageType(ByteArray(0)))
    }
}
