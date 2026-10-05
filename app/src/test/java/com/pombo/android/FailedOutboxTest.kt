package com.pombo.android

import com.pombo.android.core.SecurePrefs
import com.pombo.android.data.FailedOutboxStore
import com.pombo.android.data.FailedOutboxStoreTest
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.every
import io.mockk.unmockkObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A text whose send failed is kept per conversation, so after a restart the
 * bubble comes back "Not sent" and its Retry republishes the same message.
 * The restart is a new manager reading the same encrypted store.
 */
class FailedOutboxTest {

    private val me = com.pombo.android.core.EthereumSigner
        .address("0x4f3edf983ac636a65a842ce7c78d9aa706d3b113bce9c46f30d7d21715b23b1d").lowercase()
    private val streamId = "$me/room-1"
    /** The keypair of the sealed-sender vectors: private key 0x22…22. */
    private val peer = "0x1563915e194d8cfba1943570603f7606a3115508"
    private val peerPublicKey = "0x02466d7fcae563e5cb09a0d1870bb580344804617879a14949cf22285f1bae3f27"
    private val dmId = "$peer/Pombo-DM-1"
    private val channels = listOf(
        ChannelManagerHarness.channel(streamId),
        ChannelManagerHarness.channel(dmId, type = "dm").copy(peerAddress = peer)
    )
    private val stored = HashMap<String, String>()
    private val reply = ReplyRef(id = "parent-1", sender = peer, senderName = "Peer", text = "the question")
    private val harnesses = mutableListOf<ChannelManagerHarness>()

    @Before fun setUp() {
        mockkObject(SecurePrefs)
        every { SecurePrefs.create(any(), any(), any()) } returns FailedOutboxStoreTest.inMemoryPrefs(stored)
    }

    @After fun tearDown() {
        harnesses.forEach { it.stop() }
        unmockkObject(SecurePrefs)
    }

    private fun outbox() = FailedOutboxStore(mockk(relaxed = true)).apply { scopeAddress = me }

    /** A fresh manager over the same store: what a restart looks like to the outbox. */
    private fun launch(): ChannelManagerHarness =
        ChannelManagerHarness(channels = channels, failedOutbox = outbox()).also { harnesses += it }

    private fun publishThrows(h: ChannelManagerHarness, reason: String) {
        coEvery { h.bridge.call("publishAsChannel", any()) } throws IllegalStateException(reason)
    }

    private fun publishSucceeds(h: ChannelManagerHarness) {
        coEvery { h.bridge.call("publishAsChannel", any()) } answers {
            h.published += secondArg<JSONObject>().toString()
            JSONObject()
        }
    }

    private fun textPublishes(h: ChannelManagerHarness, id: String) =
        h.published.filter { it.contains("\"type\":\"text\"") && it.contains("\"id\":\"$id\"") }

    private fun failSendInRoom(): UiMessage {
        val h = launch()
        h.manager.openChannel(streamId)
        publishThrows(h, "No network")
        runCatching { runBlocking { h.manager.sendMessage("hello", reply) } }
        h.stop()
        return outbox().load(streamId).single().let { e ->
            UiMessage(e.getString("id"), e.getString("text"), e.getString("sender"), null, e.getLong("timestamp"), mine = true)
        }
    }

    @Test
    fun `a failed send is kept with everything its retry republishes`() {
        failSendInRoom()

        val entry = outbox().load(streamId).single()
        assertEquals("hello", entry.getString("text"))
        assertEquals(me, entry.getString("sender"))
        assertEquals("parent-1", entry.getJSONObject("replyTo").getString("id"))
        assertEquals("No network", entry.getString("failError"))
        assertTrue(entry.getLong("timestamp") > 0)
    }

    @Test
    fun `after a restart the bubble comes back not sent, and its retry republishes the same message`() {
        val failed = failSendInRoom()

        val h = launch()
        h.manager.openChannel(streamId)
        val bubble = h.manager.messages.value.single()
        assertEquals(failed.id, bubble.id)
        assertTrue(bubble.failed)
        assertTrue(bubble.mine)
        assertEquals("No network", bubble.failError)
        assertEquals("parent-1", bubble.replyTo?.id)

        publishSucceeds(h)
        runBlocking { h.manager.resendMessage(failed.id) }

        val sent = JSONObject(textPublishes(h, failed.id).single())
        val content = sent.optJSONObject("content") ?: sent
        assertEquals(failed.timestamp, content.getLong("timestamp"))
        assertEquals("parent-1", content.getJSONObject("replyTo").getString("id"))
        assertFalse(h.manager.messages.value.single().failed)
        assertTrue(outbox().load(streamId).isEmpty())
    }

    @Test
    fun `a retry that fails again keeps the entry, with the new reason`() {
        val failed = failSendInRoom()

        val h = launch()
        h.manager.openChannel(streamId)
        publishThrows(h, "Still offline")
        runCatching { runBlocking { h.manager.resendMessage(failed.id) } }

        assertEquals("Still offline", outbox().load(streamId).single().getString("failError"))
    }

    @Test
    fun `our own copy arriving clears the bubble and the entry`() {
        val failed = failSendInRoom()

        val h = launch()
        h.manager.openChannel(streamId)
        h.deliver(streamId, com.pombo.android.core.StreamConstants.P_MESSAGES, JSONObject()
            .put("type", "text").put("id", failed.id).put("text", "hello")
            .put("sender", me).put("timestamp", failed.timestamp), from = me)

        assertFalse(h.manager.messages.value.single { it.id == failed.id }.failed)
        assertTrue(outbox().load(streamId).isEmpty())
    }

    @Test
    fun `deleting the failed bubble drops it for good`() {
        val failed = failSendInRoom()

        val h = launch()
        h.manager.openChannel(streamId)
        runCatching { runBlocking { h.manager.deleteMessage(failed.id) } }

        assertTrue(outbox().load(streamId).isEmpty())
        val again = launch()
        again.manager.openChannel(streamId)
        assertTrue(again.manager.messages.value.isEmpty())
    }

    @Test
    fun `leaving the conversation drops its outbox`() {
        failSendInRoom()

        val h = launch()
        h.manager.removeChannel(streamId)

        assertTrue(outbox().load(streamId).isEmpty())
    }

    @Test
    fun `a sent text storage never recorded is kept as undelivered`() {
        val h = launch()
        h.manager.openChannel(streamId)
        publishSucceeds(h)
        runBlocking { h.manager.sendMessage("hello") }
        val id = h.manager.messages.value.single().id
        assertTrue(outbox().load(streamId).isEmpty())

        h.manager.markUndelivered(id, "Never reached storage")
        h.manager.keepUndeliveredForRetry(streamId, id)

        val entry = outbox().load(streamId).single()
        assertEquals(id, entry.getString("id"))
        assertTrue(entry.getBoolean("undelivered"))
    }

    @Test
    fun `a DM that failed comes back after a restart and its retry seals it`() {
        val h1 = launch()
        h1.manager.openChannel(dmId)
        runCatching { runBlocking { h1.manager.sendMessage("hello") } }
        val id = h1.manager.messages.value.single().id
        h1.stop()

        assertEquals(id, outbox().load(dmId).single().getString("id"))
        val h = launch()
        h.manager.openChannel(dmId)
        // The DM open assembles its timeline off the caller's thread.
        runBlocking { withTimeout(5_000) { while (h.manager.messages.value.isEmpty()) delay(20) } }
        val bubble = h.manager.messages.value.single()
        assertEquals(id, bubble.id)
        assertTrue(bubble.failed)

        var sealed = 0
        coEvery { h.bridge.call("publishAs", any(), any()) } answers {
            if (secondArg<JSONObject>().optString("streamId") == dmId) sealed += 1
            JSONObject().put("timestamp", System.currentTimeMillis())
        }
        coEvery { h.bridge.call("getPeerPublicKey", any()) } returns JSONObject().put("publicKey", peerPublicKey)
        runBlocking { h.manager.resendMessage(id) }

        assertEquals(1, sealed)
        assertNull(h.manager.messages.value.single().failError)
        assertTrue(outbox().load(dmId).isEmpty())
    }
}
