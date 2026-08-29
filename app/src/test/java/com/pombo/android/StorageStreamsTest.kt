package com.pombo.android

import com.pombo.android.data.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which streams a channel stores, and how a node that reached only some of
 * them is reported. Storage on the -4 is what makes the epoch-key protocol
 * asynchronous, so a settings screen that quietly leaves it out lets a gated
 * channel lose its KEY_ANNOUNCEs while reporting itself healthy.
 *
 * Mirrors the web's ttlRepublish.test.js "channel storage covers every stored
 * stream" block.
 */
class StorageStreamsTest {

    private fun channel(type: String, keysStreamId: String = "0xowner/test-4") = Channel(
        messageStreamId = "0xowner/test-1",
        ephemeralStreamId = "0xowner/test-2",
        adminStreamId = "0xowner/test-3",
        keysStreamId = keysStreamId,
        name = "T",
        type = type
    )

    // ===== storedStreams =====

    @Test
    fun `a gated channel stores the message, admin and keys streams`() {
        assertEquals(
            listOf("0xowner/test-1", "0xowner/test-3", "0xowner/test-4"),
            ChannelManager.storedStreams(channel("gated"))
        )
    }

    @Test
    fun `a public channel has no keys stream to store`() {
        assertEquals(
            listOf("0xowner/test-1", "0xowner/test-3"),
            ChannelManager.storedStreams(channel("public"))
        )
    }

    @Test
    fun `the ephemeral stream is never stored`() {
        ChannelManager.storedStreams(channel("gated")).forEach {
            assertFalse("the ephemeral stream must never take storage", it.endsWith("-2"))
        }
    }

    @Test
    fun `the keys stream is derived when the record does not carry it`() {
        assertEquals(
            "0xowner/test-4",
            ChannelManager.storedStreams(channel("gated", keysStreamId = "")).last()
        )
    }

    // ===== StorageNode.partial =====

    private fun node(
        onMessage: Boolean, onAdmin: Boolean, onKeys: Boolean,
        hasKeys: Boolean, allStreamsRead: Boolean = true
    ) = ChannelManager.StorageNode("0xnode", onMessage, onAdmin, onKeys, hasKeys, allStreamsRead)

    @Test
    fun `a node on every stored stream is complete`() {
        assertFalse(node(onMessage = true, onAdmin = true, onKeys = true, hasKeys = true).partial)
    }

    @Test
    fun `a node missing from the keys stream is partial`() {
        assertTrue(node(onMessage = true, onAdmin = true, onKeys = false, hasKeys = true).partial)
    }

    @Test
    fun `a node missing from the admin stream is partial`() {
        assertTrue(node(onMessage = true, onAdmin = false, onKeys = true, hasKeys = true).partial)
    }

    /** Without a -4, onKeys can never be true and must not read as a gap. */
    @Test
    fun `a channel with no keys stream is complete on the two it has`() {
        assertFalse(node(onMessage = true, onAdmin = true, onKeys = false, hasKeys = false).partial)
    }

    /**
     * A lookup that timed out says nothing about that stream. Reading its
     * silence as a missing assignment sends the admin to pay for a repair
     * that may not be needed.
     */
    @Test
    fun `a node is never called partial when a stream lookup failed`() {
        assertFalse(node(
            onMessage = false, onAdmin = true, onKeys = true,
            hasKeys = true, allStreamsRead = false
        ).partial)
    }
}
