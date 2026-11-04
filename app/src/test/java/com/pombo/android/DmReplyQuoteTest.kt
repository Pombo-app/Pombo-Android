package com.pombo.android

import io.mockk.coEvery
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A DM reply carries its quote like a channel reply: on the bubble that is
 * sent, on the wire and in the sent record, and on the bubble rebuilt from a
 * received or a stored message.
 */
class DmReplyQuoteTest {

    /** The keypair of the sealed-sender vectors: private key 0x22…22. */
    private val peer = "0x1563915e194d8cfba1943570603f7606a3115508"
    private val peerPublicKey = "0x02466d7fcae563e5cb09a0d1870bb580344804617879a14949cf22285f1bae3f27"
    private val dmId = "$peer/Pombo-DM-1"
    private val h = ChannelManagerHarness(
        channels = listOf(ChannelManagerHarness.channel(dmId, type = "dm").copy(peerAddress = peer))
    )
    private val manager = h.manager
    private val reply = ReplyRef(id = "parent-1", sender = peer, senderName = "Peer", text = "the question")

    @After fun tearDown() = h.stop()

    private fun quoteOf(r: ReplyRef) = JSONObject()
        .put("id", r.id).put("sender", r.sender).put("senderName", r.senderName).put("text", r.text)

    private fun openAndWait(): List<UiMessage> {
        manager.openChannel(dmId)
        runBlocking { withTimeout(5_000) { while (manager.messages.value.isEmpty()) delay(20) } }
        return manager.messages.value
    }

    @Test
    fun `a sent DM reply shows its quote and records it with the message`() {
        coEvery { h.bridge.call("publishAs", any(), any()) } returns JSONObject().put("timestamp", System.currentTimeMillis())
        coEvery { h.bridge.call("getPeerPublicKey", any()) } returns JSONObject().put("publicKey", peerPublicKey)
        manager.openChannel(dmId)

        runBlocking { manager.sendMessage("the answer", reply) }

        assertEquals(reply, manager.messages.value.single().replyTo)
        verify {
            manager.sentDmStore.add(dmId, match { it.optJSONObject("replyTo")?.optString("id") == reply.id })
        }
    }

    @Test
    fun `a received DM reply shows its quote`() {
        manager.subscribeMyInbox()
        val inbox = "${h.me}/Pombo-DM-1"
        val ts = System.currentTimeMillis() - 60_000
        manager.onIncoming(
            inbox, 0,
            JSONObject().put("type", "text").put("id", "answer-1").put("text", "the answer")
                .put("timestamp", ts).put("replyTo", quoteOf(reply)).toString(),
            JSONObject().put("publisherId", peer).put("timestamp", ts).toString()
        )

        assertEquals(reply, openAndWait().single { it.id == "answer-1" }.replyTo)
    }

    @Test
    fun `a stored DM reply keeps its quote when the conversation is rebuilt`() {
        every { manager.sentDmStore.load(dmId) } returns listOf(
            JSONObject().put("type", "text").put("id", "answer-2").put("text", "the answer")
                .put("sender", h.me).put("timestamp", System.currentTimeMillis())
                .put("replyTo", quoteOf(reply))
        )

        assertEquals(reply, openAndWait().single { it.id == "answer-2" }.replyTo)
    }
}
