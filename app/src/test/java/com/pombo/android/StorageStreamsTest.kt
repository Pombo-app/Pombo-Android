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

    // ===== which streams a write actually has to touch =====
    //
    // Every storage operation is one transaction per stream. A stream that
    // is already correct must cost nothing, and a stream that could not be
    // read must be written to anyway: skipping on an unknown leaves it
    // diverged with the UI reporting success.

    private val NODE = "0xae340e799e8151f6a4999d245e466197aa217667"

    private fun stream(
        read: Boolean = true,
        nodes: List<String> = emptyList(),
        storageDays: Int? = null
    ) = ChannelManager.StoredStream("s-1", "message", read, nodes, storageDays)

    @Test
    fun `a stream already at the target retention needs no write`() {
        assertFalse(ChannelManager.needsRetentionWrite(stream(storageDays = 180), 180))
    }

    @Test
    fun `a stream at a different retention needs a write`() {
        assertTrue(ChannelManager.needsRetentionWrite(stream(storageDays = 3), 180))
    }

    @Test
    fun `a retention that could not be read is written anyway`() {
        assertTrue(ChannelManager.needsRetentionWrite(stream(read = false, storageDays = 180), 180))
    }

    @Test
    fun `a stream already carrying the node needs no add`() {
        assertFalse(ChannelManager.needsNodeAdd(stream(nodes = listOf(NODE)), NODE))
    }

    @Test
    fun `a stream missing the node needs an add`() {
        assertTrue(ChannelManager.needsNodeAdd(stream(nodes = emptyList()), NODE))
    }

    @Test
    fun `node matching ignores address case`() {
        assertFalse(ChannelManager.needsNodeAdd(stream(nodes = listOf(NODE.uppercase())), NODE))
    }

    @Test
    fun `a stream that could not be read is added to anyway`() {
        assertTrue(ChannelManager.needsNodeAdd(stream(read = false, nodes = listOf(NODE)), NODE))
    }

    @Test
    fun `a stream without the node needs no removal`() {
        assertFalse(ChannelManager.needsNodeRemove(stream(nodes = emptyList()), NODE))
    }

    @Test
    fun `a stream carrying the node needs removal`() {
        assertTrue(ChannelManager.needsNodeRemove(stream(nodes = listOf(NODE)), NODE))
    }

    /**
     * An unread stream has an empty node list, which looks exactly like one
     * that never carried it. Skipping there would leave the node assigned
     * with the UI reporting it removed.
     */
    @Test
    fun `a stream that could not be read is removed from anyway`() {
        assertTrue(ChannelManager.needsNodeRemove(stream(read = false), NODE))
    }

    // ===== did the writes land? =====

    @Test
    fun `converged when every stream answered and needs nothing`() {
        val after = listOf(stream(storageDays = 180), stream(storageDays = 180))
        assertTrue(ChannelManager.writesConverged(after) { ChannelManager.needsRetentionWrite(it, 180) })
    }

    @Test
    fun `not converged when a stream still needs the write`() {
        val after = listOf(stream(storageDays = 180), stream(storageDays = 3))
        assertFalse(ChannelManager.writesConverged(after) { ChannelManager.needsRetentionWrite(it, 180) })
    }

    /**
     * Silence is not success. Even a predicate that claims nothing is needed
     * cannot make an unread stream count as confirmed: the read-back exists
     * to catch exactly the case where we cannot see what happened.
     */
    @Test
    fun `not converged when a stream stopped answering`() {
        val after = listOf(stream(storageDays = 180), stream(read = false, storageDays = 180))
        assertFalse(ChannelManager.writesConverged(after) { false })
    }

    // ===== can the admin press Save? =====

    @Test
    fun `a new retention can be saved`() {
        assertTrue(ChannelManager.canSaveRetention(30, 180, true))
    }

    @Test
    fun `saving the value already shown is pointless when the streams agree`() {
        assertFalse(ChannelManager.canSaveRetention(180, 180, true))
    }

    /**
     * The panel shows the message stream. When another stream differs, the
     * warning tells the admin to save the same figure again, so blocking it
     * would make its own advice unfollowable.
     */
    @Test
    fun `saving the value already shown heals streams that disagree`() {
        assertTrue(ChannelManager.canSaveRetention(180, 180, false))
    }

    @Test
    fun `a retention below one day is never saveable`() {
        assertFalse(ChannelManager.canSaveRetention(0, 180, false))
    }
}