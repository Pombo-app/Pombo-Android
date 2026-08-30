package com.pombo.android

import com.pombo.android.core.StreamConstants
import io.mockk.coEvery
import io.mockk.every
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Message verification: the badge under a message and the trust ladder behind
 * it. Two paths meet here — a legacy signature recovered in a batch, and the
 * current format where identity was already settled at ingest — and the replay
 * guard runs on both.
 *
 * `verified` has three states and they are not interchangeable: true (checked),
 * false (checked and wrong, a red flag) and null (nothing to check, no badge).
 */
class MessageVerificationTest {

    private val owner = "0xowner"
    private val streamId = "$owner/room-1"
    private val room = ChannelManagerHarness.channel(streamId)
    private val peer = "0xpeer"
    private val trusted = "0xtrusted"

    private val h = ChannelManagerHarness(channels = listOf(room), trustedContacts = setOf(trusted))
    private val manager = h.manager

    @Before fun setUp() = manager.openChannel(streamId)
    @After fun tearDown() = h.stop()

    /** The batch answer the bridge would give for a set of recovered addresses. */
    private fun recovers(vararg addresses: String) {
        coEvery { h.bridge.call(eq("verifyCanonicalBatch"), any(), any()) } returns
            JSONObject().put("addresses", JSONArray(addresses.toList()))
    }

    private fun bridgeFails() {
        coEvery { h.bridge.call(eq("verifyCanonicalBatch"), any(), any()) } throws
            IllegalStateException("bridge is down")
    }

    private fun text(
        id: String = "m1",
        sender: String = peer,
        signature: String? = "0xsig",
        timestamp: Long = System.currentTimeMillis(),
        from: String? = peer
    ) {
        val content = JSONObject().put("type", "text").put("id", id)
            .put("text", "hello").put("sender", sender).put("timestamp", timestamp)
        signature?.let { content.put("signature", it) }
        h.deliver(streamId, StreamConstants.P_MESSAGES, content, from ?: "")
    }

    /** The batch waits VERIFY_BATCH_WINDOW_MS before it flushes. */
    private fun afterFlush() = Thread.sleep(ChannelManager.VERIFY_BATCH_WINDOW_MS + 400)

    private fun msg(id: String = "m1") = manager.messages.value.find { it.id == id }

    @Test
    fun `a signature that recovers to the sender verifies the message`() {
        recovers(peer)
        text()
        afterFlush()
        assertEquals(true, msg()!!.verified)
    }

    @Test
    fun `a signature that recovers to someone else does not verify`() {
        recovers("0xsomeoneelse")
        text()
        afterFlush()
        assertEquals(false, msg()!!.verified)
    }

    @Test
    fun `a signature that recovers to nothing does not verify`() {
        recovers("")
        text()
        afterFlush()
        assertEquals(false, msg()!!.verified)
    }

    /** A bridge that is down must fail the batch closed, not leave it unbadged. */
    @Test
    fun `a bridge failure marks the whole batch unverified`() {
        bridgeFails()
        text(id = "m1")
        text(id = "m2")
        afterFlush()
        assertEquals(false, msg("m1")!!.verified)
        assertEquals(false, msg("m2")!!.verified)
    }

    /**
     * Current format (D6): no app-layer signature. Identity was settled at
     * ingest by the publisher proof, so the account is authority enough.
     */
    @Test
    fun `an unsigned message is verified from its account`() {
        text(signature = null)
        assertEquals(true, msg()!!.verified)
    }

    /** No account at all is "nothing to check", not "check failed". */
    @Test
    fun `an unsigned message with no account gets no badge`() {
        text(signature = null, from = "")
        assertNull(msg()!!.verified)
    }

    /** The replay guard: a FRESH message whose clock is far off is refused. */
    @Test
    fun `a fresh message with a wildly wrong timestamp is refused`() {
        recovers(peer)
        text(timestamp = System.currentTimeMillis() + ChannelManager.TIMESTAMP_TOLERANCE_MS * 2)
        assertEquals(false, msg()!!.verified)
    }

    /**
     * And it applies only to fresh ones: a resend routinely delivers old
     * messages, and failing those for being old is the bug the age check
     * exists to avoid.
     */
    @Test
    fun `an old message is not refused for being old`() {
        recovers(peer)
        text(timestamp = System.currentTimeMillis() - 60 * 60_000)
        afterFlush()
        assertEquals(true, msg()!!.verified)
    }

    @Test
    fun `a verified sender who is a trusted contact gets the top trust level`() {
        recovers(trusted)
        text(sender = trusted, from = trusted)
        afterFlush()
        assertEquals(2, msg()!!.trustLevel)
    }

    @Test
    fun `a verified sender with an ENS name gets the middle trust level`() {
        every { h.ensStore.cachedName(peer) } returns "peer.eth"
        coEvery { h.ensStore.name(any(), any()) } returns "peer.eth"
        recovers(peer)
        text()
        afterFlush()
        assertEquals(1, msg()!!.trustLevel)
    }

    @Test
    fun `a verified sender who is neither stays at the base trust level`() {
        recovers(peer)
        text()
        afterFlush()
        assertEquals(0, msg()!!.trustLevel)
    }

    /**
     * A failed signature grants no trust level of its own. It does NOT bar one:
     * [ChannelManager.resolveEnsFor] raises every message from a sender whose
     * ENS resolves, on purpose and independently of the signature — the badge
     * says "this name is real", not "this message is verified", and the two
     * are shown separately.
     */
    @Test
    fun `a failed signature grants no trust level of its own`() {
        recovers("0xsomeoneelse")
        text()
        afterFlush()
        assertFalse(msg()!!.verified!!)
        assertEquals(0, msg()!!.trustLevel)
    }
}

