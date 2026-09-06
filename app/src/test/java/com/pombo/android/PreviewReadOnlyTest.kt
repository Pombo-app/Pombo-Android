package com.pombo.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A previewed channel has no record on disk, so every flag the chat screen
 * reads has to be carried over from Explore. readOnly is the one with teeth:
 * the composer asks it whether a publish is possible at all, and the network
 * refuses what it wrongly allows — silently, at ingest.
 */
class PreviewReadOnlyTest {

    private val h = ChannelManagerHarness()
    private val manager = h.manager

    @After fun tearDown() = h.stop()

    private fun explore(readOnly: Boolean) = ExploreChannel(
        messageStreamId = "0xowner/announcements-1",
        name = "StreamOperator",
        description = "Announcements",
        type = "public",
        readOnly = readOnly
    )

    @Test
    fun `preview of a read-only channel is read-only`() {
        manager.previewChannel(explore(readOnly = true))
        assertEquals("0xowner/announcements-1", manager._current.value?.messageStreamId)
        assertTrue(manager._current.value?.readOnly == true)
    }

    @Test
    fun `preview of an ordinary channel stays writable`() {
        manager.previewChannel(explore(readOnly = false))
        assertFalse(manager._current.value?.readOnly == true)
    }
}
