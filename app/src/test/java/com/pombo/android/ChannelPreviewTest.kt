package com.pombo.android

import android.content.Context
import com.pombo.android.core.LatestMessageStore
import com.pombo.android.core.StreamConstants
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

private val OWNER = "0x" + "cd".repeat(20)

/**
 * The list preview reads the same rows the timeline does, so it must apply
 * what the timeline applies: the author's own edits and deletes, and the
 * forgery clamps. Leaving the channel hands the list what was on screen.
 */
class ChannelPreviewTest {

    private val owner = OWNER
    private val other = "0x" + "ef".repeat(20)
    private val room = ChannelManagerHarness.channel("$owner/room-1")
    private val gatedRoom = ChannelManagerHarness.channel("$owner/gated-1", type = "gated")
    private val previews = LatestMessageStore(
        mockk<Context>().also { every { it.filesDir } returns Files.createTempDirectory("pombo-previews").toFile() }
    ).apply { memoryOnly = true }
    private val h = ChannelManagerHarness(channels = listOf(room, gatedRoom), previewStore = previews)
    private val manager = h.manager
    private val t0 = System.currentTimeMillis() - 60_000

    /** A row as the bridge serves it: payload, publisher, envelope time and, when known, when the node stored it. */
    private data class Row(val content: JSONObject, val from: String = OWNER, val at: Long, val storedAt: Long? = null)

    private var p0 = listOf<Row>()
    private var p1 = listOf<Row>()

    @Before fun setUp() {
        manager.adminConfirmSleep = { }
        manager.historyRetryDelaysMs = longArrayOf(600_000L, 600_000L, 600_000L, 600_000L)
        every { manager.adminFloorStore.get(any()) } returns null
        coEvery { h.bridge.call("resend", any(), any()) } answers {
            val args = secondArg<JSONObject>()
            if (args.optString("streamId") != room.messageStreamId) page(emptyList())
            else when (args.optInt("partition")) {
                StreamConstants.P_MESSAGES -> page(p0)
                StreamConstants.P_CONTROL -> page(p1)
                else -> page(emptyList())
            }
        }
    }

    @After fun tearDown() = h.stop()

    private fun page(rows: List<Row>) = JSONObject().put("messages", JSONArray(rows.map { row ->
        val meta = JSONObject().put("publisherId", row.from).put("timestamp", row.at)
        row.storedAt?.let { meta.put("storedAt", it) }
        JSONObject().put("content", row.content).put("meta", meta)
    }))

    private fun text(id: String, body: String, at: Long) =
        JSONObject().put("type", "text").put("id", id).put("text", body).put("timestamp", at)

    private fun edit(targetId: String, body: String, at: Long) =
        JSONObject().put("type", "edit").put("targetId", targetId).put("text", body).put("timestamp", at)

    private fun delete(targetId: String, at: Long) =
        JSONObject().put("type", "delete").put("targetId", targetId).put("timestamp", at)

    private fun announce(fileName: String, fileType: String, at: Long) = JSONObject()
        .put("type", "storage_file_announce").put("id", fileName).put("timestamp", at)
        .put("metadata", JSONObject().put("fileName", fileName).put("fileType", fileType))

    private fun latest() = runBlocking { manager.fetchLatestPreview(room.messageStreamId) }

    @Test
    fun `the author's own edit shows, anyone else's does not`() {
        p0 = listOf(Row(text("m1", "first", t0), at = t0), Row(text("m2", "second", t0 + 1), from = other, at = t0 + 1))
        p1 = listOf(
            Row(edit("m1", "first, edited", t0 + 2), at = t0 + 2),
            Row(edit("m2", "hijacked", t0 + 3), at = t0 + 3)
        )

        assertEquals("second", latest()?.text)

        p0 = p0.take(1)
        assertEquals("first, edited", latest()?.text)
    }

    @Test
    fun `the author's delete falls back to the message before it`() {
        p0 = listOf(Row(text("m1", "first", t0), at = t0), Row(text("m2", "second", t0 + 1), at = t0 + 1))
        p1 = listOf(Row(delete("m2", t0 + 2), at = t0 + 2))

        assertEquals("first", latest()?.text)
    }

    @Test
    fun `a row dated ahead of the clock, or ahead of when the node stored it, never shows`() {
        val ahead = System.currentTimeMillis() + 3_600_000
        p0 = listOf(
            Row(text("m1", "real", t0), at = t0),
            Row(text("m2", "from the future", ahead), at = ahead),
            Row(text("m3", "backdated store", t0 + 5), at = t0 + 5, storedAt = t0 + 5 - 3_600_000)
        )

        assertEquals("real", latest()?.text)
    }

    @Test
    fun `media is labelled by what it is`() {
        p0 = listOf(Row(announce("clip.mp4", "video/mp4", t0), at = t0))
        assertEquals("🎬 Video", latest()?.text)

        p0 = listOf(Row(announce("report.pdf", "application/pdf", t0), at = t0))
        assertEquals("📎 report.pdf", latest()?.text)

        p0 = listOf(Row(JSONObject().put("type", "audio").put("id", "a1").put("imageId", "a1").put("timestamp", t0), at = t0))
        assertEquals("🎤 Voice message", latest()?.text)
    }

    @Test
    fun `a scan never moves the unread watermark past the clock`() {
        val ahead = System.currentTimeMillis() + 3_600_000
        p0 = listOf(Row(text("m1", "real", t0), at = t0), Row(text("m2", "future", ahead), at = ahead))

        runBlocking { manager.scanChannelActivity(room) }

        verify { h.unreadStore.setWatermark(room.messageStreamId, t0) }
        verify(exactly = 0) { h.unreadStore.setWatermark(room.messageStreamId, ahead) }
        assertEquals("real", previews.cached(room.messageStreamId)?.text)
    }

    @Test
    fun `leaving a channel hands the list its newest message on screen, over a newer stored line`() {
        p0 = listOf(Row(text("m1", "on screen", t0), at = t0))
        manager.openChannel(room.messageStreamId)
        runBlocking {
            previews.put(room.messageStreamId, LatestMessageStore.Preview("x", "deleted since", t0 + 10_000, owner))
        }

        manager.closeCurrent()

        assertEquals("on screen", previews.cached(room.messageStreamId)?.text)
    }

    @Test
    fun `a moderator's message dropped on a cold cache is counted by the next scan`() {
        val gate = "0x" + "9a".repeat(20)
        val news = gatedRoom.copy(gateAddress = gate, readOnly = true)
        coEvery { h.bridge.call("gateIsModerator", any(), any()) } returns JSONObject().put("moderator", true)
        coEvery { h.bridge.call("resend", any(), any()) } returns JSONObject().put("messages", JSONArray().put(JSONObject()
            .put("content", text("m1", "from a moderator", t0))
            .put("meta", JSONObject().put("publisherId", gate).put("signer", other).put("timestamp", t0))))

        runBlocking { manager.scanChannelActivity(news) }

        verify(exactly = 0) { h.unreadStore.setWatermark(news.messageStreamId, t0) }
        verify(exactly = 0) { h.unreadStore.add(news.messageStreamId, any()) }

        runBlocking { manager.scanChannelActivity(news) }

        verify { h.unreadStore.add(news.messageStreamId, 1) }
        verify { h.unreadStore.setWatermark(news.messageStreamId, t0) }
    }

    @Test
    fun `leaving a channel marks read up to its newest message on screen, from a clock ahead of mine too`() {
        val ahead = System.currentTimeMillis() + 60_000
        p0 = listOf(Row(text("m1", "read", ahead), at = ahead))
        manager.openChannel(room.messageStreamId)

        manager.closeCurrent()

        verify { h.unreadStore.clear(room.messageStreamId, ahead) }
    }

    @Test
    fun `an account switch marks nothing read in the next account's store`() {
        p0 = listOf(Row(text("m1", "read", t0), at = t0))
        manager.openChannel(room.messageStreamId)

        manager.reloadChannels()

        verify(exactly = 0) { h.unreadStore.clear(room.messageStreamId, t0) }
    }

    @Test
    fun `a channel closed by an account switch leaves nothing in the next account's previews`() {
        p0 = listOf(Row(text("m1", "the last account's secret", t0), at = t0))
        manager.openChannel(room.messageStreamId)

        manager.reloadChannels()

        assertNull(previews.cached(room.messageStreamId))
    }

    @Test
    fun `a gated read asks the bridge for the envelope signer, the only proof of authorship there`() {
        val asked = mutableListOf<JSONObject>()
        coEvery { h.bridge.call("resend", any(), any()) } answers {
            asked += secondArg<JSONObject>()
            JSONObject().put("messages", JSONArray().put(JSONObject()
                .put("content", JSONObject().put("type", "text").put("id", "m1").put("text", "hi"))
                .put("meta", JSONObject().put("publisherId", owner).put("timestamp", t0))))
        }

        runBlocking { manager.scanChannelActivity(gatedRoom) }

        val read = asked.first { it.optString("streamId") == gatedRoom.messageStreamId }
        assertEquals(true, read.optBoolean("recoverSigner"))
        assertEquals(true, read.optBoolean("gated"))
    }

    @Test
    fun `a stored line dated ahead gives way to a real one`() = runBlocking {
        val ahead = System.currentTimeMillis() + 3_600_000
        val s = LatestMessageStore(
            mockk<Context>().also { every { it.filesDir } returns Files.createTempDirectory("pombo-previews").toFile() }
        ).apply { memoryOnly = true }

        s.put(room.messageStreamId, LatestMessageStore.Preview("x", "future", ahead, owner))
        assertNull(s.cached(room.messageStreamId))

        s.put(room.messageStreamId, LatestMessageStore.Preview("x", "real", t0, owner))
        assertEquals("real", s.cached(room.messageStreamId)?.text)
    }
}
