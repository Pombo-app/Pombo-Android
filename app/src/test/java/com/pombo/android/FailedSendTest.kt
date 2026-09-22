package com.pombo.android

import io.mockk.coEvery
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A text whose publish throws stays on the timeline marked failed, with the
 * reason, and a retry publishes it again under the same id.
 */
class FailedSendTest {

    private val me = com.pombo.android.core.EthereumSigner
        .address("0x4f3edf983ac636a65a842ce7c78d9aa706d3b113bce9c46f30d7d21715b23b1d").lowercase()
    private val streamId = "$me/room-1"
    /** The keypair of the sealed-sender vectors: private key 0x22…22. */
    private val peer = "0x1563915e194d8cfba1943570603f7606a3115508"
    private val peerPublicKey = "0x02466d7fcae563e5cb09a0d1870bb580344804617879a14949cf22285f1bae3f27"
    private val dmId = "$peer/Pombo-DM-1"
    private val h = ChannelManagerHarness(channels = listOf(
        ChannelManagerHarness.channel(streamId),
        ChannelManagerHarness.channel(dmId, type = "dm").copy(peerAddress = peer)
    ))
    private val manager = h.manager

    @Before fun setUp() {
        manager.openChannel(streamId)
    }

    @After fun tearDown() = h.stop()

    private fun bubble(): UiMessage = manager.messages.value.single()

    private fun textPublishes(id: String) =
        h.published.count { it.contains("\"type\":\"text\"") && it.contains("\"id\":\"$id\"") }

    private fun publishThrows(reason: String) {
        coEvery { h.bridge.call("publishAsChannel", any()) } throws IllegalStateException(reason)
    }

    private fun publishSucceeds() {
        coEvery { h.bridge.call("publishAsChannel", any()) } answers {
            h.published += secondArg<JSONObject>().toString()
            JSONObject()
        }
    }

    @Test
    fun `a publish that throws leaves the bubble marked failed with the reason`() {
        publishThrows("No epoch key")

        val error = runCatching { runBlocking { manager.sendMessage("hello") } }.exceptionOrNull()

        assertEquals("No epoch key", error?.message)
        assertEquals("hello", bubble().text)
        assertTrue(bubble().failed)
        assertFalse(bubble().pending)
        assertEquals("No epoch key", bubble().failError)
        assertEquals(0, textPublishes(bubble().id))
    }

    @Test
    fun `a retry publishes the failed message again under the same id`() {
        publishThrows("No epoch key")
        runCatching { runBlocking { manager.sendMessage("hello") } }
        val id = bubble().id

        publishSucceeds()
        runBlocking { manager.resendMessage(id) }

        assertEquals(1, manager.messages.value.size)
        assertEquals(id, bubble().id)
        assertFalse(bubble().failed)
        assertFalse(bubble().pending)
        assertNull(bubble().failError)
        assertEquals(1, textPublishes(id))
    }

    @Test
    fun `a retry that fails again keeps the bubble failed`() {
        publishThrows("No epoch key")
        runCatching { runBlocking { manager.sendMessage("hello") } }
        val id = bubble().id

        publishThrows("Still no key")
        val error = runCatching { runBlocking { manager.resendMessage(id) } }.exceptionOrNull()

        assertEquals("Still no key", error?.message)
        assertTrue(bubble().failed)
        assertEquals("Still no key", bubble().failError)
    }

    @Test
    fun `a DM without the peer key is marked failed, and a retry with the key seals and publishes it`() {
        manager.openChannel(dmId)
        // The wake signal after a send goes out the same way; count only the inbox.
        var sealedPublishes = 0
        coEvery { h.bridge.call("publishAs", any(), any()) } answers {
            if (secondArg<JSONObject>().optString("streamId") == dmId) sealedPublishes += 1
            JSONObject().put("timestamp", System.currentTimeMillis())
        }

        val error = runCatching { runBlocking { manager.sendMessage("hello") } }.exceptionOrNull()

        assertTrue(error?.message?.contains("peer public key unavailable") == true)
        assertTrue(bubble().failed)
        assertFalse(bubble().pending)
        assertEquals(0, sealedPublishes)
        val id = bubble().id

        coEvery { h.bridge.call("getPeerPublicKey", any()) } returns JSONObject().put("publicKey", peerPublicKey)
        runBlocking { manager.resendMessage(id) }

        assertEquals(1, manager.messages.value.size)
        assertEquals(id, bubble().id)
        assertFalse(bubble().failed)
        assertFalse(bubble().pending)
        assertNull(bubble().failError)
        assertEquals(1, sealedPublishes)
    }

    @Test
    fun `a message that did not fail is not resent`() {
        publishSucceeds()
        runBlocking { manager.sendMessage("hello") }
        val id = bubble().id
        assertEquals(1, textPublishes(id))

        runBlocking { manager.resendMessage(id) }

        assertEquals(1, textPublishes(id))
        assertFalse(bubble().failed)
    }
}
