package com.pombo.android

import com.pombo.android.core.channels.DeliveryConfirm
import io.mockk.coEvery
import kotlinx.coroutines.runBlocking
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
 * A published message is read back from storage until it is there; still
 * missing at its last read it is marked undelivered, and a node that never
 * answered proves nothing either way.
 */
class DeliveryConfirmTest {

    private val me = com.pombo.android.core.EthereumSigner
        .address("0x4f3edf983ac636a65a842ce7c78d9aa706d3b113bce9c46f30d7d21715b23b1d").lowercase()
    private val streamId = "$me/room-1"
    private val h = ChannelManagerHarness(channels = listOf(ChannelManagerHarness.channel(streamId)))
    private val manager = h.manager

    private val t0 = 1_000_000L
    private var clock = t0
    /** What storage serves for the window; null means the read failed. */
    private var rows: () -> List<Long>? = { emptyList() }
    private var reads = 0
    private val envelopeTs = 5000L

    @Before fun setUp() {
        manager.delivery.sleep = { ms -> clock += ms }
        manager.delivery.now = { clock }
        coEvery { h.bridge.call("resolveStorageEndpoints", any()) } returns JSONObject().put("nodes", JSONArray().put(
            JSONObject().put("nodeAddress", "0x1").put("urls", JSONArray().put("https://node.example"))))
        coEvery { h.bridge.call("resendEnvelopes", any(), any()) } answers {
            reads += 1
            val served = rows() ?: throw IllegalStateException("503")
            JSONObject().put("rows", JSONArray().apply { served.forEach { put(JSONObject().put("timestamp", it)) } })
        }
        coEvery { h.bridge.call("publishAsChannel", any()) } answers {
            h.published += secondArg<JSONObject>().toString()
            JSONObject().put("timestamp", envelopeTs)
        }
        manager.openChannel(streamId)
    }

    @After fun tearDown() = h.stop()

    private fun bubble(): UiMessage = manager.messages.value.single()

    @Test
    fun `a message storage serves back is delivered`() {
        rows = { listOf(envelopeTs) }

        runBlocking { manager.sendMessage("hello") }

        assertTrue(bubble().delivered)
        assertFalse(bubble().failed)
        assertEquals(1, reads)
        assertEquals(t0 + DeliveryConfirm.CONFIRM_DELAYS_MS[0], clock)
        assertEquals(0, manager.delivery.pending(streamId))
    }

    @Test
    fun `a message every read came back without is undelivered, with a way to retry`() {
        rows = { emptyList() }

        runBlocking { manager.sendMessage("hello") }

        assertEquals(DeliveryConfirm.CONFIRM_DELAYS_MS.size, reads)
        assertTrue(bubble().failed)
        assertTrue(bubble().undelivered)
        assertFalse(bubble().delivered)
        assertEquals(DeliveryConfirm.UNDELIVERED_REASON, bubble().failError)
        assertEquals(t0 + DeliveryConfirm.CONFIRM_DELAYS_MS.sum(), clock)
    }

    @Test
    fun `a message is left as sent when storage never answered`() {
        rows = { null }

        runBlocking { manager.sendMessage("hello") }

        assertEquals(DeliveryConfirm.CONFIRM_DELAYS_MS.size, reads)
        assertFalse(bubble().failed)
        assertFalse(bubble().delivered)
        assertNull(bubble().failError)
        assertEquals(0, manager.delivery.pending(streamId))
    }

    @Test
    fun `a retry of an undelivered message publishes again and is confirmed`() {
        rows = { emptyList() }
        runBlocking { manager.sendMessage("hello") }
        val id = bubble().id
        assertTrue(bubble().undelivered)

        rows = { listOf(envelopeTs) }
        runBlocking { manager.resendMessage(id) }

        assertEquals(id, bubble().id)
        assertFalse(bubble().failed)
        assertFalse(bubble().undelivered)
        assertNull(bubble().failError)
        assertTrue(bubble().delivered)
        assertEquals(2, h.published.count { it.contains("\"id\":\"$id\"") })
    }

    @Test
    fun `a channel without storage is not read back`() {
        coEvery { h.bridge.call("resolveStorageEndpoints", any()) } returns JSONObject().put("nodes", JSONArray())
        // The open already resolved and cached the providers; read them again.
        runBlocking { manager.storageEndpoints.resolve(streamId, force = true) }

        runBlocking { manager.sendMessage("hello") }

        assertEquals(0, reads)
        assertFalse(bubble().failed)
        assertFalse(bubble().delivered)
    }
}
