package com.pombo.android

import com.pombo.android.ui.screens.collapseWhitespace
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Message previews get a fixed number of lines. A body with blank lines in it
 * used to spend them on the blank lines instead of on words.
 */
class PreviewTextTest {

    @Test
    fun `blank lines collapse to a single space`() {
        val body = "Update 📢\r\n\r\nThe Owner's Cut parameter will remain at 7%"
        assertEquals(
            "Update 📢 The Owner's Cut parameter will remain at 7%",
            collapseWhitespace(body)
        )
    }

    @Test
    fun `a body that starts with a newline does not render an empty first line`() {
        assertEquals("first words", collapseWhitespace("\n\n  first words"))
    }

    @Test
    fun `runs of spaces and tabs become one space`() {
        assertEquals("a b c", collapseWhitespace("a   b\t\tc"))
    }

    @Test
    fun `ordinary text is untouched`() {
        assertEquals("Streamr 2026 Q2 report", collapseWhitespace("Streamr 2026 Q2 report"))
    }

    @Test
    fun `an empty body stays empty`() {
        assertEquals("", collapseWhitespace("   \n\t "))
    }
}
