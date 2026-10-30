package com.pombo.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity with the web's SubscriptionBannerUI.formatRemaining — the two
 * clients must describe the same time-left the same way (N-F).
 */
class GateFormatTest {

    @Test
    fun formatRemaining_days() {
        assertEquals("12 days", GateFormat.formatRemaining(12L * 86_400_000))
        assertEquals("1 day", GateFormat.formatRemaining(1L * 86_400_000))
        // 47h59m is still "1 day" — floors, never rounds up
        assertEquals("1 day", GateFormat.formatRemaining(2L * 86_400_000 - 60_000))
    }

    @Test
    fun formatRemaining_hours() {
        assertEquals("5h", GateFormat.formatRemaining(5L * 3_600_000))
        assertEquals("23h", GateFormat.formatRemaining(24L * 3_600_000 - 1))
    }

    @Test
    fun formatRemaining_subHour() {
        assertEquals("less than an hour", GateFormat.formatRemaining(30L * 60_000))
        assertEquals("less than an hour", GateFormat.formatRemaining(1))
    }

    @Test
    fun period_namesTheLargestUnitItReaches() {
        assertEquals("day", GateFormat.period(86_400))
        assertEquals("30 days", GateFormat.period(30L * 86_400))
        assertEquals("6 hours", GateFormat.period(6L * 3_600))
        assertEquals("10 minutes", GateFormat.period(600))
        assertEquals("45 seconds", GateFormat.period(45))
        assertTrue(GateFormat.period(90L * 60).matches(Regex("1[.,]5 hours")))
    }

    @Test
    fun warningWindow_isThreeDays() {
        assertEquals(3L * 24 * 60 * 60 * 1000, GateFormat.WARNING_MS)
    }
}
