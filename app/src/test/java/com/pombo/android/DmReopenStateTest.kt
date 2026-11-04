package com.pombo.android

import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the peer does to a conversation reaches me only through my inbox, which
 * is replayed once per session: their reactions, edits and deletes have to
 * survive leaving and reopening it, and still show when they arrived while it
 * was closed.
 */
class DmReopenStateTest {

    private val peer = "0x1563915e194d8cfba1943570603f7606a3115508"
    private val dmId = "$peer/Pombo-DM-1"
    private val roomId = "0x0000000000000000000000000000000000000001/room-1"
    private val h = ChannelManagerHarness(
        channels = listOf(
            ChannelManagerHarness.channel(dmId, type = "dm").copy(peerAddress = peer),
            ChannelManagerHarness.channel(roomId)
        )
    )
    private val manager = h.manager
    private val inbox = "${h.me}/Pombo-DM-1"
    private val mine = "mine-1"
    private val theirs = "theirs-1"
    private var clock = System.currentTimeMillis() - 600_000

    @Before fun setUp() {
        every { manager.sentDmStore.load(dmId) } returns listOf(
            JSONObject().put("type", "text").put("id", mine).put("text", "hello")
                .put("sender", h.me).put("timestamp", clock)
        )
        manager.subscribeMyInbox()
        fromPeer(JSONObject().put("type", "text").put("id", theirs).put("text", "first"))
    }

    @After fun tearDown() = h.stop()

    private fun fromPeer(payload: JSONObject) {
        clock += 1_000
        manager.onIncoming(
            inbox, 0, payload.put("timestamp", clock).toString(),
            JSONObject().put("publisherId", peer).put("timestamp", clock).toString()
        )
    }

    private fun react(messageId: String, emoji: String, action: String = "add") = fromPeer(
        JSONObject().put("type", "reaction").put("messageId", messageId).put("emoji", emoji).put("action", action)
    )

    private fun edit(text: String) =
        fromPeer(JSONObject().put("type", "edit").put("targetId", theirs).put("text", text))

    private fun delete() = fromPeer(JSONObject().put("type", "delete").put("targetId", theirs))

    private fun open(streamId: String) {
        manager.openChannel(streamId)
        runBlocking {
            withTimeout(5_000) { while (manager.current.value?.messageStreamId != streamId) delay(20) }
            delay(300)
        }
    }

    private fun reopen() { open(roomId); open(dmId) }

    private fun peerEmojis(messageId: String): Set<String> =
        manager.reactions.value[messageId].orEmpty().filterValues { peer in it }.keys

    private fun theirsShown() = manager.messages.value.firstOrNull { it.id == theirs }

    @Test
    fun `a reaction seen in the open conversation is still there after reopening it`() {
        open(dmId)
        react(mine, "🔥")
        assertEquals(setOf("🔥"), peerEmojis(mine))

        reopen()

        assertEquals(setOf("🔥"), peerEmojis(mine))
    }

    @Test
    fun `a reaction that arrived while the conversation was closed shows when it opens`() {
        open(roomId)
        react(mine, "💯")

        open(dmId)

        assertEquals(setOf("💯"), peerEmojis(mine))
    }

    @Test
    fun `a reaction the peer took back while the conversation was closed is gone when it opens`() {
        open(dmId)
        react(mine, "🔥")
        open(roomId)
        react(mine, "🔥", action = "remove")

        open(dmId)

        assertTrue(peerEmojis(mine).isEmpty())
    }

    @Test
    fun `an edit seen in the open conversation is still there after reopening it`() {
        open(dmId)
        edit("second")

        reopen()

        assertEquals("second", theirsShown()?.text)
        assertTrue(theirsShown()!!.edited)
    }

    @Test
    fun `an edit that arrived while the conversation was closed shows when it opens`() {
        open(roomId)
        edit("second")

        open(dmId)

        assertEquals("second", theirsShown()?.text)
    }

    @Test
    fun `an edited message keeps its reactions after reopening`() {
        open(dmId)
        react(theirs, "👍")
        open(roomId)
        edit("second")

        open(dmId)

        assertEquals("second", theirsShown()?.text)
        assertEquals(setOf("👍"), peerEmojis(theirs))
    }

    @Test
    fun `a delete seen in the open conversation holds after reopening`() {
        open(dmId)
        delete()

        reopen()

        assertFalse(manager.messages.value.any { it.id == theirs })
    }

    @Test
    fun `a delete that arrived while the conversation was closed holds when it opens`() {
        open(roomId)
        delete()

        open(dmId)

        assertFalse(manager.messages.value.any { it.id == theirs })
    }

    @Test
    fun `a deleted message read again from the inbox stays deleted`() {
        open(roomId)
        delete()
        fromPeer(JSONObject().put("type", "text").put("id", theirs).put("text", "first"))

        open(dmId)

        assertFalse(manager.messages.value.any { it.id == theirs })
    }

    private fun olderPageHolds(id: String, text: String) {
        val ts = System.currentTimeMillis() - 3_600_000L
        coEvery { h.bridge.call(eq("resendWindow"), any(), any()) } returns JSONObject()
            .put("messages", JSONArray().put(JSONObject()
                .put("content", JSONObject().put("type", "text").put("id", id).put("text", text).put("timestamp", ts))
                .put("meta", JSONObject().put("publisherId", peer).put("timestamp", ts)
                    .put("sequenceNumber", 0).put("storedAt", ts + 1_000L))))
            .put("hasMore", false)
    }

    @Test
    fun `an edit to a message only an older page holds applies when that page loads`() {
        fromPeer(JSONObject().put("type", "edit").put("targetId", "old-1").put("text", "edited"))
        open(dmId)
        olderPageHolds("old-1", "original")

        runBlocking { manager.loadMoreDmHistory() }

        assertEquals("edited", manager.messages.value.firstOrNull { it.id == "old-1" }?.text)
    }

    @Test
    fun `a delete of a message only an older page holds keeps it out when that page loads`() {
        fromPeer(JSONObject().put("type", "delete").put("targetId", "old-2"))
        open(dmId)
        olderPageHolds("old-2", "original")

        runBlocking { manager.loadMoreDmHistory() }

        assertFalse(manager.messages.value.any { it.id == "old-2" })
    }

    @Test
    fun `an edited message read again from the inbox keeps the edit`() {
        open(roomId)
        edit("second")
        fromPeer(JSONObject().put("type", "text").put("id", theirs).put("text", "first"))

        open(dmId)

        assertEquals("second", theirsShown()?.text)
    }
}
