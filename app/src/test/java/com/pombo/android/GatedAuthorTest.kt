package com.pombo.android

import com.pombo.android.core.StreamConstants
import com.pombo.android.data.Channel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Who wrote a message on a gated channel — and, as often, that nobody did.
 *
 * The transport publisher is the gate clone for everyone, so authorship comes
 * from the recovered envelope signer. The admin stream is the exception the
 * owner publishes under their own account, and there the claim alone is not
 * enough: gated reads are raw, so nothing else checked that signature.
 */
class GatedAuthorTest {

    private val owner = "0x" + "aa".repeat(20)
    private val gate = "0x" + "bb".repeat(20)
    private val stranger = "0x" + "cc".repeat(20)

    private val streamId = "$owner/room-1"
    private val adminId = "$owner/room-3"

    private val h = ChannelManagerHarness()

    @After fun tearDown() = h.stop()

    private fun channel() = Channel(
        messageStreamId = streamId,
        ephemeralStreamId = StreamConstants.deriveEphemeralId(streamId),
        adminStreamId = adminId,
        name = "gated",
        type = "gated",
        gateAddress = gate,
        createdBy = owner
    )

    private fun meta(publisher: String, signer: String? = null) = JSONObject()
        .put("publisherId", publisher)
        .apply { signer?.let { put("signer", it) } }

    @Test
    fun `a clone-published message is attributed to its envelope signer`() {
        assertEquals(
            stranger,
            h.manager.gatedAuthor(channel(), streamId, meta(gate, stranger))
        )
    }

    @Test
    fun `a foreign publisher on a gated stream is dropped`() {
        assertNull(h.manager.gatedAuthor(channel(), streamId, meta(stranger, stranger)))
    }

    @Test
    fun `the owner publishing the admin stream as themselves is accepted`() {
        assertEquals(
            owner,
            h.manager.gatedAuthor(channel(), adminId, meta(owner, owner))
        )
    }

    /**
     * The claim is not the proof: an envelope that merely NAMES the owner
     * would otherwise hand a forged snapshot the channel's bans, hidden
     * messages and pins.
     */
    @Test
    fun `an admin message naming the owner but signed by someone else is dropped`() {
        assertNull(h.manager.gatedAuthor(channel(), adminId, meta(owner, stranger)))
    }

    @Test
    fun `an admin message with no recovered signer is dropped`() {
        assertNull(h.manager.gatedAuthor(channel(), adminId, meta(owner)))
    }

    /** Clone-published admin history still has to be the admin's. */
    @Test
    fun `a clone-published admin message from a non-admin is dropped`() {
        assertNull(h.manager.gatedAuthor(channel(), adminId, meta(gate, stranger)))
    }
    /**
     * Read-only, Everyone mode: the gate grants publish to every member (the
     * contract sees a hash, never a stream), so a member CAN put a message on
     * the wire. Only readers can hold "members do not post".
     */
    @Test
    fun `on a read-only channel a member is not an author`() {
        val ro = channel().copy(readOnly = true)
        assertNull(h.manager.gatedAuthor(ro, streamId, meta(gate, stranger)))
    }

    @Test
    fun `the owner still writes their own read-only channel`() {
        val ro = channel().copy(readOnly = true)
        assertEquals(owner, h.manager.gatedAuthor(ro, streamId, meta(gate, owner)))
    }

    /** The keys stream is the members' own: a read-only gate never cuts it. */
    @Test
    fun `a member key message survives on a read-only channel`() {
        val ro = channel().copy(readOnly = true)
        val keysId = StreamConstants.deriveKeysId(streamId)
        assertEquals(stranger, h.manager.gatedAuthor(ro, keysId, meta(gate, stranger)))
    }
}
