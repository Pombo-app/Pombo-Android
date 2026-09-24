package com.pombo.android

import io.mockk.coEvery
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * With no usable network a send fails at once, keeps the bubble for Retry, and
 * never reaches the bridge.
 */
class OfflineSendTest {

    private val me = com.pombo.android.core.EthereumSigner
        .address("0x4f3edf983ac636a65a842ce7c78d9aa706d3b113bce9c46f30d7d21715b23b1d").lowercase()
    private val streamId = "$me/room-1"
    private val peer = "0x1563915e194d8cfba1943570603f7606a3115508"
    private val dmId = "$peer/Pombo-DM-1"
    private val gatedId = "$me/gated-1"
    private val h = ChannelManagerHarness(channels = listOf(
        ChannelManagerHarness.channel(streamId),
        ChannelManagerHarness.channel(dmId, type = "dm").copy(peerAddress = peer),
        ChannelManagerHarness.channel(gatedId, type = "gated").copy(gateAddress = "0x" + "cd".repeat(20))
    ))
    private val manager = h.manager

    @Before fun setUp() {
        coEvery { h.bridge.call("publishAsChannel", any()) } answers {
            h.published += secondArg<JSONObject>().toString()
            JSONObject()
        }
    }

    @After fun tearDown() = h.stop()

    private fun bubble(): UiMessage = manager.messages.value.single()

    private fun textPublishes() = h.published.count { it.contains("\"type\":\"text\"") }

    /** Counts calls of [method] from now on (the open itself makes some), optionally on one stream. */
    private fun countCalls(method: String, streamId: String? = null): () -> Int {
        var n = 0
        val count = { args: JSONObject -> if (streamId == null || args.optString("streamId") == streamId) n++ }
        coEvery { h.bridge.call(method, any()) } answers { count(secondArg()); JSONObject() }
        coEvery { h.bridge.call(method, any(), any()) } answers { count(secondArg()); JSONObject() }
        return { n }
    }

    private fun sendOffline(): Pair<Throwable?, Long> {
        val t0 = System.currentTimeMillis()
        val error = runCatching { runBlocking { manager.sendMessage("hello") } }.exceptionOrNull()
        return error to System.currentTimeMillis() - t0
    }

    @Test
    fun `a send with no network fails at once and keeps the bubble for Retry`() {
        manager.openChannel(streamId)
        h.online = false

        val (error, elapsed) = sendOffline()

        assertEquals(ChannelManager.NO_NETWORK, error?.message)
        assertTrue("took ${elapsed}ms", elapsed < 1_000)
        assertTrue(bubble().failed)
        assertFalse(bubble().pending)
        assertEquals(ChannelManager.NO_NETWORK, bubble().failError)
        assertEquals(0, textPublishes())
    }

    @Test
    fun `a Retry with no network fails again without publishing, and goes out once the network is back`() {
        manager.openChannel(streamId)
        h.online = false
        sendOffline()
        val id = bubble().id

        val again = runCatching { runBlocking { manager.resendMessage(id) } }.exceptionOrNull()
        assertEquals(ChannelManager.NO_NETWORK, again?.message)
        assertTrue(bubble().failed)
        assertEquals(0, textPublishes())

        h.online = true
        runBlocking { manager.resendMessage(id) }
        assertFalse(bubble().failed)
        assertEquals(1, h.published.count { it.contains("\"id\":\"$id\"") })
    }

    @Test
    fun `a DM with no network fails at once without publishing`() {
        manager.openChannel(dmId)
        val sealed = countCalls("publishAs", streamId = dmId)
        h.online = false

        val (error, elapsed) = sendOffline()

        assertEquals(ChannelManager.NO_NETWORK, error?.message)
        assertTrue("took ${elapsed}ms", elapsed < 1_000)
        assertTrue(bubble().failed)
        assertEquals(0, sealed())
    }

    @Test
    fun `a gated send with no network does not ask the gate and fails with the bubble`() {
        manager.openChannel(gatedId)
        val gateAsks = countCalls("gateCheckAccess")
        h.online = false

        val (error, _) = sendOffline()

        assertEquals(ChannelManager.NO_NETWORK, error?.message)
        assertTrue(bubble().failed)
        assertEquals(0, gateAsks())
    }
}
