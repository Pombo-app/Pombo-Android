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
    fun `stored ids round-trip and anything else falls back to Direct`() {
        HomeTab.entries.forEach { assertEquals(it, HomeTab.fromId(it.id)) }
        assertEquals(HomeTab.DIRECT, HomeTab.fromId(null))
        assertEquals(HomeTab.DIRECT, HomeTab.fromId("community"))
    }
}
