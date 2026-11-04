package com.pombo.android

import io.mockk.coEvery
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two accounts on one device that both talk to the same peer: what one
 * account received from that peer stays with it when the device switches to
 * the other, whether the switch comes after its inbox replay or in the middle
 * of it.
 */
class DmAccountSwitchTest {

    private val peer = "0x7ea24eb97d400a76f8d96be92c4e7fce576aedb9"
    private val other = "0x03e2b466754f187f571ab48c69e3ab592e76d819"
    private val conversation = "$peer/Pombo-DM-1"
    private val h = ChannelManagerHarness(
        channels = listOf(ChannelManagerHarness.channel(conversation, type = "dm").copy(peerAddress = peer))
    )
    private val manager = h.manager

    @After fun tearDown() = h.stop()

    private fun dm(id: String) = JSONObject()
        .put("type", "text").put("id", id).put("text", id).put("timestamp", System.currentTimeMillis())

    /** What the view model does on a switch: a new identity, then the stores re-read under it. */
    private fun switchTo(address: String) {
        manager.closeCurrent()
        h.address = address
        manager.reloadChannels()
    }

    private suspend fun shownIds(): List<String> {
        manager.openChannel(conversation)
        delay(300)
        return manager.messages.value.map { it.id }
    }

    @Test
    fun `what one account received from a peer is not shown to the next account`() = runBlocking {
        manager.subscribeMyInbox()
        h.deliver("${h.me}/Pombo-DM-1", 0, dm("for-the-first-account"), peer)

        switchTo(other)

        assertTrue("for-the-first-account" !in shownIds())
    }

    @Test
    fun `an inbox replay the switch cuts through does not land in the next account`() = runBlocking {
        val replay = JSONObject().put("messages", JSONArray().put(JSONObject()
            .put("content", dm("replayed"))
            .put("meta", JSONObject().put("publisherId", peer).put("timestamp", System.currentTimeMillis()))))
        var firstPage = true
        coEvery { h.bridge.call(eq("resend"), any(), any()) } answers {
            if (!firstPage) return@answers JSONObject()
            firstPage = false
            // The switch lands while the first account's page is in flight.
            switchTo(other)
            replay
        }

        manager.subscribeMyInbox()
        delay(200)

        assertEquals(1, manager.channels.value.count { it.peerAddress.equals(peer, ignoreCase = true) })
        assertTrue("replayed" !in shownIds())
    }
}
