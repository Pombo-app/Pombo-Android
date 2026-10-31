package com.pombo.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeTabsTest {

    @Test
    fun `DMs go to Direct and every other type to Channels`() {
        assertEquals(HomeTab.DIRECT, HomeTab.of("dm"))
        listOf("public", "password", "gated", null).forEach {
            assertEquals(HomeTab.CHANNELS, HomeTab.of(it))
        }
    }

    @Test
    fun `Channels comes first and is where the Home opens`() {
        assertEquals(listOf(HomeTab.CHANNELS, HomeTab.DIRECT), HomeTab.entries.toList())
        assertEquals(HomeTab.CHANNELS, HomeTab.DEFAULT)
    }

    @Test
    fun `stored ids round-trip, a remembered tab winning, and anything else falls back to Channels`() {
        HomeTab.entries.forEach { assertEquals(it, HomeTab.fromId(it.id)) }
        assertEquals(HomeTab.DIRECT, HomeTab.fromId("direct"))
        assertEquals(HomeTab.CHANNELS, HomeTab.fromId(null))
        assertEquals(HomeTab.CHANNELS, HomeTab.fromId("community"))
    }
}
