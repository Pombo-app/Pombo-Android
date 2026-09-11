package com.pombo.android

import io.mockk.coEvery
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The older-page reader of a DM inbox judges rows the way the first page
 * does: a row the node stored long before the instant it claims is a
 * planted one, and stays out whatever the wall clock says now.
 */
class DmOlderPageTest {

    private val peer = "0x7ea24eb97d400a76f8d96be92c4e7fce576aedb9"
    private val conversation = "$peer/Pombo-DM-1"
    private val h = ChannelManagerHarness(
        channels = listOf(ChannelManagerHarness.channel(conversation, type = "dm").copy(peerAddress = peer))
    )
    private val manager = h.manager

    @After fun tearDown() = h.stop()

    private fun row(id: String, envelopeTs: Long, storedAt: Long) = JSONObject()
        .put("content", JSONObject().put("type", "text").put("id", id).put("text", id).put("timestamp", envelopeTs))
        .put("meta", JSONObject().put("publisherId", peer).put("timestamp", envelopeTs)
            .put("sequenceNumber", 0).put("storedAt", storedAt))

    @Test
    fun `a refused older page raises the history error and ends paging`() = runBlocking {
        coEvery { h.bridge.call(eq("resendWindow"), any(), any()) } returns JSONObject()
            .put("messages", JSONArray())
            .put("hasMore", true)
            .put("readError", JSONObject().put("status", 503).put("signed", true).put("reason", "storedAt"))

        manager.openChannel(conversation)
        val page = manager.loadMoreDmHistory()

        assertEquals(false, page.hasMore)
        assertEquals("storedAt", manager.historyError.value?.reason)
        assertEquals(503, manager.historyError.value?.status)
    }

    @Test
    fun `a forward-dated row of an older page is dropped, the genuine one kept with its coordinates`() = runBlocking {
        val now = System.currentTimeMillis()
        val forgedTs = now - 30 * 60_000L
        val genuineTs = now - 40 * 60_000L
        coEvery { h.bridge.call(eq("resendWindow"), any(), any()) } returns JSONObject()
            .put("messages", JSONArray()
                .put(row("forged", forgedTs, forgedTs - 3_600_000L))
                .put(row("genuine", genuineTs, genuineTs + 2_000L)))
            .put("hasMore", false)

        manager.openChannel(conversation)
        val page = manager.loadMoreDmHistory()

        assertEquals(1, page.loaded)
        val shown = manager.messages.value.filter { it.sender.equals(peer, ignoreCase = true) }
        assertEquals(listOf("genuine"), shown.map { it.id })
        assertEquals(genuineTs, shown.single().envelopeTs)
        assertEquals(0, shown.single().seq)
        assertEquals(peer, shown.single().publisherId)
    }
}
