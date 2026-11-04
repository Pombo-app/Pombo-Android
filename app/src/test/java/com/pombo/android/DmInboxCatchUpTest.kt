package com.pombo.android

import io.mockk.coEvery
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The inbox catch-up reads storage from the newest inbox row already routed
 * and routes what it finds like live traffic, so a DM the live subscription
 * missed still arrives.
 */
class DmInboxCatchUpTest {

    private val peer = "0x7ea24eb97d400a76f8d96be92c4e7fce576aedb9"
    private val conversation = "$peer/Pombo-DM-1"
    private val h = ChannelManagerHarness(
        channels = listOf(ChannelManagerHarness.channel(conversation, type = "dm").copy(peerAddress = peer))
    )
    private val manager = h.manager
    private val inbox = "${h.me}/Pombo-DM-1"

    /** Every resendWindow request, in order. */
    private val windows = mutableListOf<JSONObject>()
    /** What the next resendWindow answers. */
    private var answer: (JSONObject) -> JSONObject = { JSONObject().put("messages", JSONArray()) }

    private fun dm(id: String, ts: Long) = JSONObject()
        .put("type", "text").put("id", id).put("text", id).put("timestamp", ts)

    private fun row(id: String, ts: Long) = JSONObject()
        .put("content", dm(id, ts))
        .put("meta", JSONObject().put("publisherId", peer).put("timestamp", ts).put("sequenceNumber", 0))

    /** A live DM as the subscription hands it over, stamped with its envelope time. */
    private fun live(id: String, ts: Long) = manager.onIncoming(
        inbox, 0, dm(id, ts).toString(),
        JSONObject().put("publisherId", peer).put("timestamp", ts).toString()
    )

    @Before fun setUp() {
        coEvery { h.bridge.call(eq("resendWindow"), any(), any()) } answers {
            val a = secondArg<JSONObject>()
            windows += a
            answer(a)
        }
        manager.subscribeMyInbox()
    }

    @After fun tearDown() = h.stop()

    private suspend fun shownIds(): List<String> {
        manager.openChannel(conversation)
        delay(300)
        return manager.messages.value.map { it.id }
    }

    @Test
    fun `nothing is read before a first inbox row was routed`() = runBlocking {
        assertEquals(0, manager.catchUpInbox("tick"))
        assertTrue(windows.isEmpty())
    }

    @Test
    fun `it reads from the newest routed row and delivers what the live subscription missed`() = runBlocking {
        val seen = System.currentTimeMillis() - 60_000
        live("seen", seen)
        answer = { JSONObject().put("messages", JSONArray().put(row("missed", seen + 1_000))) }

        assertEquals(1, manager.catchUpInbox("tick"))

        val w = windows.single()
        assertEquals(seen, w.getLong("before") - w.getLong("windowMs"))
        assertEquals(inbox, w.getString("streamId"))
        answer = { JSONObject().put("messages", JSONArray()) }
        assertTrue("missed" in shownIds())
    }

    @Test
    fun `a row the live subscription already delivered is not shown twice`() = runBlocking {
        val ts = System.currentTimeMillis() - 60_000
        live("once", ts)
        answer = { JSONObject().put("messages", JSONArray().put(row("once", ts))) }

        manager.catchUpInbox("tick")

        assertEquals(1, shownIds().count { it == "once" })
    }

    @Test
    fun `a read that did not finish keeps the starting point`() = runBlocking {
        val seen = System.currentTimeMillis() - 60_000
        live("seen", seen)
        answer = { JSONObject().put("messages", JSONArray().put(row("half", seen + 1_000))).put("partial", true) }

        assertEquals(0, manager.catchUpInbox("tick"))
        answer = { JSONObject().put("messages", JSONArray()) }
        manager.catchUpInbox("tick")

        assertEquals(seen, windows.last().getLong("before") - windows.last().getLong("windowMs"))
    }

    @Test
    fun `an account switch drops the starting point`() = runBlocking {
        live("seen", System.currentTimeMillis() - 60_000)

        h.address = "0x03e2b466754f187f571ab48c69e3ab592e76d819"
        manager.reloadChannels()

        assertEquals(0, manager.catchUpInbox("tick"))
        assertTrue(windows.isEmpty())
    }

    @Test
    fun `opening a conversation always reads the inbox`() = runBlocking {
        live("seen", System.currentTimeMillis() - 60_000)

        shownIds()

        assertTrue(windows.isNotEmpty())
    }
}
