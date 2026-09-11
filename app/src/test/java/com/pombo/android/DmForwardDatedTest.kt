package com.pombo.android

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A DM's payload timestamp is the sender's to write. Live traffic never
 * meets a storage node's receive time, so the inbox router applies the same
 * clock clamp the channel ingest does: nothing dated beyond the tolerance
 * ahead of the wall clock or of its own envelope gets in.
 */
class DmForwardDatedTest {

    private val h = ChannelManagerHarness()
    private val manager = h.manager
    private val peer = "0x7ea24eb97d400a76f8d96be92c4e7fce576aedb9"
    private val inbox = "${h.me}/Pombo-DM-1"

    @After fun tearDown() = h.stop()

    private fun dm(id: String, timestamp: Long) = JSONObject()
        .put("type", "text").put("id", id).put("text", id).put("timestamp", timestamp)

    private fun hasConversation() =
        manager.channels.value.any { it.type == "dm" && it.peerAddress?.equals(peer, ignoreCase = true) == true }

    @Test
    fun `a live DM dated beyond the clock tolerance opens no conversation`() {
        manager.subscribeMyInbox()
        h.deliver(inbox, 0, dm("future", System.currentTimeMillis() + 3_600_000L), peer)
        assertTrue(!hasConversation())
        h.deliver(inbox, 0, dm("fine", System.currentTimeMillis()), peer)
        assertTrue(hasConversation())
    }

    @Test
    fun `a live DM dated ahead of its own envelope beyond tolerance is dropped`() {
        manager.subscribeMyInbox()
        val now = System.currentTimeMillis()
        manager.onIncoming(
            inbox, 0, dm("ahead", now).toString(),
            JSONObject().put("publisherId", peer).put("timestamp", now - 600_000L).toString()
        )
        assertTrue(!hasConversation())
    }
}
