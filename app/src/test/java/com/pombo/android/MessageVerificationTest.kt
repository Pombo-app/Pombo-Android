package com.pombo.android

import com.pombo.android.core.StreamConstants
import io.mockk.coEvery
import io.mockk.every
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Message verification: the author badge under a message and the trust ladder
 * behind it. The badge is anti-spoofing: only a publisher proof that recovered
 * at ingest vouches for the author.
 *
 * `verified` has three states and they are not interchangeable: true (proven),
 * false (a fresh message failed the clock check, a red flag) and null (nothing
 * proven, no badge).
 */
class MessageVerificationTest {

    private val owner = "0xowner"
    private val streamId = "$owner/room-1"
    private val room = ChannelManagerHarness.channel(streamId)
    private val peer = "0xpeer"
    private val trusted = "0xtrusted"
    private val ephemeral = "0xephemeral"

    private val h = ChannelManagerHarness(channels = listOf(room), trustedContacts = setOf(trusted))
    private val manager = h.manager

    @Before fun setUp() = manager.openChannel(streamId)
    @After fun tearDown() = h.stop()

    /** The account the bridge recovers from any proof. */
    private fun proofRecovers(account: String) {
        coEvery { h.bridge.call(eq("recoverPublisherProofBatch"), any(), any()) } returns
            JSONObject().put("accounts", JSONArray(listOf(account)))
    }

    private fun text(
        id: String = "m1",
        proof: String? = "0xproof",
        signature: String? = null,
        timestamp: Long = System.currentTimeMillis(),
        from: String = ephemeral,
        extra: JSONObject.() -> Unit = {}
    ) {
        val content = JSONObject().put("type", "text").put("id", id)
            .put("text", "hello").put("timestamp", timestamp)
        proof?.let { content.put("proof", it) }
        signature?.let { content.put("signature", it).put("sender", from) }
        content.extra()
        h.deliver(streamId, StreamConstants.P_MESSAGES, content, from)
    }

    private fun msg(id: String = "m1") = manager.messages.value.find { it.id == id }

    @Test
    fun `a proof that recovers vouches for the account`() {
        proofRecovers(peer)
        text()
        assertEquals(peer, msg()!!.sender)
        assertEquals(true, msg()!!.verified)
    }

    @Test
    fun `no proof means no badge, not a red flag`() {
        text(proof = null)
        assertNull(msg()!!.verified)
    }

    @Test
    fun `a proven mark shipped on the wire is ignored`() {
        text(proof = null) { put(ChannelManager.ACCOUNT_PROVEN, true) }
        assertNull(msg()!!.verified)
    }

    /** Legacy signed messages are attributed to their address, never badged. */
    @Test
    fun `a legacy signed message gets no badge`() {
        text(proof = null, signature = "0xsig", from = peer)
        assertNull(msg()!!.verified)
    }

    /** A future-dated payload never reaches the list: the §3.6 ingest clamp
     *  drops it before verification could even badge it. */
    @Test
    fun `a fresh message with a wildly wrong timestamp is refused`() {
        proofRecovers(peer)
        text(timestamp = System.currentTimeMillis() + ChannelManager.TIMESTAMP_TOLERANCE_MS * 2)
        assertNull(msg())
    }

    /**
     * The clock check applies only to fresh messages: a resend routinely
     * delivers old ones, and failing those for being old is the bug the age
     * check exists to avoid.
     */
    @Test
    fun `an old message is not refused for being old`() {
        proofRecovers(peer)
        text(timestamp = System.currentTimeMillis() - 60 * 60_000)
        assertEquals(true, msg()!!.verified)
    }

    @Test
    fun `a proven sender who is a trusted contact gets the top trust level`() {
        proofRecovers(trusted)
        text()
        assertEquals(2, msg()!!.trustLevel)
    }

    @Test
    fun `a proven sender with an ENS name gets the middle trust level`() {
        every { h.ensStore.cachedName(peer) } returns "peer.eth"
        coEvery { h.ensStore.name(any(), any()) } returns "peer.eth"
        proofRecovers(peer)
        text()
        assertEquals(1, msg()!!.trustLevel)
    }

    @Test
    fun `a proven sender who is neither stays at the base trust level`() {
        proofRecovers(peer)
        text()
        assertEquals(0, msg()!!.trustLevel)
    }
}
