package com.pombo.android.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class PreviewTimeTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply { clear(); set(year, month, day, hour, minute) }.timeInMillis

    private val now = at(2026, Calendar.OCTOBER, 30, 15)

    @Test
    fun `the Home time shortens as the message ages`() {
        assertEquals("09:05", previewTime(at(2026, Calendar.OCTOBER, 30, 9, 5), now))
        assertEquals("Yesterday", previewTime(at(2026, Calendar.OCTOBER, 29, 23, 59), now))
        assertEquals("Mon", previewTime(at(2026, Calendar.OCTOBER, 26, 12), now))
        assertEquals("Oct 23", previewTime(at(2026, Calendar.OCTOBER, 23, 12), now))
        assertEquals("Jul 11, 2025", previewTime(at(2025, Calendar.JULY, 11, 12), now))
        assertEquals("", previewTime(0L, now))
    }
}
