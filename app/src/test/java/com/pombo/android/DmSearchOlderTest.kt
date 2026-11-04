package com.pombo.android

import io.mockk.coEvery
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * "Search older" in a DM walks the shared inbox backwards by time: an empty
 * window moves the cursor past it, a read that did not finish leaves the
 * cursor alone, each tap searches further back until Search all, and nothing
 * is searched past the inbox retention.
 */
class DmSearchOlderTest {

    private val day = 24L * 3600 * 1000
    private val peer = "0x7ea24eb97d400a76f8d96be92c4e7fce576aedb9"
    private val conversation = "$peer/Pombo-DM-1"
    private val h = ChannelManagerHarness(
        channels = listOf(ChannelManagerHarness.channel(conversation, type = "dm").copy(peerAddress = peer))
    )
    private val manager = h.manager

    /** Every resendWindow request, in order. */
    private val windows = mutableListOf<JSONObject>()
    /** What the next windows answer; an empty window once these run out. */
    private val answers = ArrayDeque<(JSONObject) -> JSONObject>()

    private fun empty(a: JSONObject) = JSONObject()
        .put("messages", JSONArray())
        .put("hasMore", true)
        .put("windowStart", a.getLong("before") - a.getLong("windowMs"))

    private fun withPeerMessage(a: JSONObject): JSONObject {
        val ts = a.getLong("before") - 1_000
        return empty(a).put("messages", JSONArray().put(JSONObject()
            .put("content", JSONObject().put("type", "text").put("id", "found").put("text", "found").put("timestamp", ts))
            .put("meta", JSONObject().put("publisherId", peer).put("timestamp", ts).put("sequenceNumber", 0))))
    }

    private fun retention(days: Int?) {
        coEvery { h.bridge.call(eq("getStreamStorageInfo"), any(), any()) } returns
            if (days == null) JSONObject().put("ok", false)
            else JSONObject().put("ok", true).put("nodes", JSONArray()).put("storageDays", days)
    }

    @Before fun setUp() {
        retention(null)
        coEvery { h.bridge.call(eq("resendWindow"), any(), any()) } answers {
            val a = secondArg<JSONObject>()
            windows += a
            (answers.removeFirstOrNull() ?: ::empty)(a)
        }
    }

    @After fun tearDown() = h.stop()

    @Test
    fun `an empty window moves the next read past it`() = runBlocking {
        manager.openChannel(conversation)

        manager.loadMoreDmHistory()
        manager.searchOlderDm()

        val first = windows[0]
        assertEquals(first.getLong("before") - first.getLong("windowMs"), windows[1].getLong("before"))
    }

    @Test
    fun `taps search 15 days, then 2 months, then offer Search all`() = runBlocking {
        manager.openChannel(conversation)

        manager.loadMoreDmHistory()
        assertEquals(ChannelManager.DmSearchPrompt.OLDER, manager.dmSearchPrompt.value)
        manager.searchOlderDm()
        assertEquals(ChannelManager.DmSearchPrompt.OLDER, manager.dmSearchPrompt.value)
        manager.searchOlderDm()

        assertEquals(listOf(7 * day, 15 * day, 60 * day), windows.map { it.getLong("windowMs") })
        assertEquals(ChannelManager.DmSearchPrompt.ALL, manager.dmSearchPrompt.value)
    }

    @Test
    fun `Search all walks back until messages turn up, then the steps start over`() = runBlocking {
        manager.openChannel(conversation)
        manager.loadMoreDmHistory()
        manager.searchOlderDm()
        manager.searchOlderDm()
        windows.clear()
        answers += ::empty
        answers += ::empty
        answers += ::withPeerMessage

        val page = manager.searchOlderDm()

        assertEquals(listOf(60 * day, 60 * day, 60 * day), windows.map { it.getLong("windowMs") })
        assertEquals(1, page.loaded)
        assertEquals(ChannelManager.DmSearchPrompt.NONE, manager.dmSearchPrompt.value)
        windows.clear()
        manager.loadMoreDmHistory()
        manager.searchOlderDm()
        assertEquals(15 * day, windows.last().getLong("windowMs"))
    }

    @Test
    fun `a window that did not finish reading keeps the cursor and offers the same read again`() = runBlocking {
        manager.openChannel(conversation)
        manager.loadMoreDmHistory()
        answers += { a -> withPeerMessage(a).put("partial", true) }

        val page = manager.searchOlderDm()

        assertTrue(page.failed)
        assertEquals(0, page.loaded)
        assertEquals(ChannelManager.DmSearchPrompt.RETRY, manager.dmSearchPrompt.value)
        manager.retryDmSearch()
        assertEquals(windows[1].getLong("before"), windows[2].getLong("before"))
        assertEquals(windows[1].getLong("windowMs"), windows[2].getLong("windowMs"))
    }

    @Test
    fun `nothing is searched past the inbox retention, and the end says so`() = runBlocking {
        retention(10)
        manager.openChannel(conversation)

        manager.loadMoreDmHistory()
        val page = manager.searchOlderDm()

        assertEquals(false, page.hasMore)
        assertTrue(windows[1].getLong("windowMs") < 15 * day)
        assertEquals(10, manager.dmRetentionDays.value)
        assertEquals(ChannelManager.DmSearchPrompt.NONE, manager.dmSearchPrompt.value)
        windows.clear()
        manager.searchOlderDm()
        assertTrue(windows.isEmpty())
    }

    @Test
    fun `each account's search stops at its own inbox retention`() = runBlocking {
        val other = "0x03e2b466754f187f571ab48c69e3ab592e76d819"
        coEvery { h.bridge.call(eq("getStreamStorageInfo"), any(), any()) } answers {
            val days = if (secondArg<JSONObject>().getString("streamId").startsWith(other)) 180 else 2
            JSONObject().put("ok", true).put("nodes", JSONArray()).put("storageDays", days)
        }
        manager.openChannel(conversation)
        manager.loadMoreDmHistory()
        assertTrue(windows.last().getLong("windowMs") <= 2 * day)

        h.address = other
        manager.reloadChannels()
        manager.openChannel(conversation)
        manager.loadMoreDmHistory()

        assertEquals(7 * day, windows.last().getLong("windowMs"))
    }

    @Test
    fun `opening a conversation starts without the last one's prompt`() = runBlocking {
        manager.openChannel(conversation)
        manager.loadMoreDmHistory()
        assertEquals(ChannelManager.DmSearchPrompt.OLDER, manager.dmSearchPrompt.value)

        manager.openChannel(conversation)

        assertEquals(ChannelManager.DmSearchPrompt.NONE, manager.dmSearchPrompt.value)
    }
}
