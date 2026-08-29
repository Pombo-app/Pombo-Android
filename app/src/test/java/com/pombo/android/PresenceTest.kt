package com.pombo.android

import com.pombo.android.core.StreamConstants
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Presence and typing: the ephemeral-stream signals that drive the online
 * count, the online list and the typing indicator.
 *
 * Mirrors the web's presence handling in channels.js — the two clients share
 * the wire format, so a divergence here is a divergence between clients.
 */
class PresenceTest {

    private val owner = "0xowner"
    private val streamId = "$owner/room-1"
    private val other = "$owner/other-1"
    private val peer = "0xpeer"
    private val peer2 = "0xpeer2"

    private val room = ChannelManagerHarness.channel(streamId)
    private val elsewhere = ChannelManagerHarness.channel(other, name = "elsewhere")

    private val h = ChannelManagerHarness(channels = listOf(room, elsewhere))
    private val manager = h.manager

    @After fun tearDown() = h.stop()

    private fun open(id: String = streamId) = manager.openChannel(id)

    private fun typing(from: String, nickname: String?, on: String = room.ephemeralStreamId) =
        h.deliver(on, StreamConstants.EPH_CONTROL,
            JSONObject().put("type", "typing").put("nickname", nickname ?: JSONObject.NULL), from)

    private fun presence(from: String, nickname: String?, lastActive: Long = System.currentTimeMillis()) =
        h.deliver(room.ephemeralStreamId, StreamConstants.EPH_CONTROL,
            JSONObject().put("type", "presence").put("nickname", nickname ?: JSONObject.NULL)
                .put("lastActive", lastActive), from)

    @Test
    fun `a peer typing shows up in the indicator`() {
        open()
        typing(peer, "Peer")
        assertEquals(listOf(peer), manager.typingFrom.value.map { it.address })
        assertEquals("Peer", manager.typingFrom.value.single().nickname)
    }

    @Test
    fun `several peers typing are all listed, oldest signal first`() {
        open()
        typing(peer, "One")
        typing(peer2, "Two")
        assertEquals(listOf(peer, peer2), manager.typingFrom.value.map { it.address })
    }

    /** A single slot meant the last signal erased whoever was already there. */
    @Test
    fun `a repeated signal does not duplicate the peer`() {
        open()
        typing(peer, "One")
        typing(peer, "One")
        assertEquals(1, manager.typingFrom.value.size)
    }

    @Test
    fun `my own typing signal never shows me as typing`() {
        open()
        typing(h.me, "me")
        assertTrue(manager.typingFrom.value.isEmpty())
    }

    @Test
    fun `typing on a channel that is not open is ignored`() {
        open()
        typing(peer, "Peer", on = elsewhere.ephemeralStreamId)
        assertTrue(manager.typingFrom.value.isEmpty())
    }

    /** Whoever was typing was typing in the OTHER room. */
    @Test
    fun `switching channel clears the indicator`() {
        open()
        typing(peer, "Peer")
        open(other)
        assertTrue(manager.typingFrom.value.isEmpty())
    }

    @Test
    fun `a heartbeat counts the peer online and keeps the nickname`() {
        open()
        presence(peer, "Peer")
        assertEquals(1, manager.onlineCount.value)
        assertEquals(listOf(peer), manager.onlineUsers.value.map { it.address })
        assertEquals("Peer", manager.onlineUsers.value.single().nickname)
    }

    @Test
    fun `the online list is sorted by address`() {
        open()
        presence(peer2, "Two")
        presence(peer, "One")
        assertEquals(listOf(peer, peer2), manager.onlineUsers.value.map { it.address })
    }

    @Test
    fun `a second heartbeat from the same peer does not double the count`() {
        open()
        presence(peer, "Peer")
        presence(peer, "Peer")
        assertEquals(1, manager.onlineCount.value)
    }

    @Test
    fun `switching channel empties the online list`() {
        open()
        presence(peer, "Peer")
        open(other)
        assertEquals(0, manager.onlineCount.value)
        assertTrue(manager.onlineUsers.value.isEmpty())
    }

    @Test
    fun `opening a channel starts the presence heartbeat`() {
        open()
        assertTrue(h.publishedOfType("presence") >= 1)
    }

    @Test
    fun `typing is throttled to one signal every two seconds`() {
        open()
        manager.sendTyping()
        manager.sendTyping()
        assertEquals(1, h.publishedOfType("typing"))
    }

    @Test
    fun `a typing signal stops being shown once it goes stale`() {
        open()
        typing(peer, "Peer")
        assertEquals(1, manager.typingFrom.value.size)
        Thread.sleep(ChannelManager.TYPING_TTL_MS + 1_500)
        assertTrue(manager.typingFrom.value.isEmpty())
    }
}
