package com.pombo.android

import com.pombo.android.core.StreamConstants
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A read of the open that failed is not an empty channel: it is read again
 * when the bridge comes back, and gives up only after the last attempt.
 * Attempts here are driven by [ChannelManager.kickOpenReadRetry] alone, the
 * backoff being far longer than any test.
 */
class OpenReadRetryTest {

    private val owner = "0x" + "ab".repeat(20)
    private val roomA = ChannelManagerHarness.channel("$owner/room-1")
    private val roomB = ChannelManagerHarness.channel("$owner/other-1")
    private val h = ChannelManagerHarness(channels = listOf(roomA, roomB))
    private val manager = h.manager
    private val t0 = System.currentTimeMillis()

    /** Content rows each room's P0 serves once reads work. */
    private val content = mutableMapOf<String, List<JSONObject>>()
    /** -3 snapshot rows, by admin stream. */
    private val admin = mutableMapOf<String, JSONObject>()
    @Volatile private var failing = setOf<String>()
    /** Streams the client has no storage for: the SDK throws NO_STORAGE_NODES. */
    @Volatile private var unstored = setOf<String>()
    /** Streams whose storage node breaks the read off: the bridge hands back what came and the error count. */
    @Volatile private var brokenOff = setOf<String>()
    /** What the fetch wrapper recorded the node answering on a broken-off read, if anything. */
    @Volatile private var nodeAnswer: JSONObject? = null

    @Before fun setUp() {
        manager.adminConfirmSleep = { }
        manager.historyRetryDelaysMs = longArrayOf(600_000L, 600_000L, 600_000L, 600_000L)
        every { manager.adminFloorStore.get(any()) } returns null
        coEvery { h.bridge.call("resend", any(), any()) } answers {
            val args = secondArg<JSONObject>()
            val streamId = args.optString("streamId")
            val partition = args.optInt("partition")
            if (streamId in unstored) throw IllegalStateException("no storage assigned: $streamId (code=NO_STORAGE_NODES)")
            if (streamId in failing) {
                JSONObject()
            } else {
                val rows = when {
                    streamId in admin -> listOf(admin.getValue(streamId))
                    partition == StreamConstants.P_MESSAGES -> content[streamId].orEmpty()
                    else -> emptyList()
                }
                val page = JSONObject().put("messages", JSONArray(rows.map { row ->
                    JSONObject().put("content", row)
                        .put("meta", JSONObject().put("publisherId", owner).put("timestamp", row.optLong("timestamp", t0)))
                }))
                if (streamId in brokenOff) {
                    page.put("partial", true).put("errors", 1)
                    nodeAnswer?.let { page.put("readError", it) }
                }
                page
            }
        }
    }

    @After fun tearDown() = h.stop()

    private fun text(id: String, at: Long) = JSONObject().put("type", "text").put("id", id)
        .put("text", "hello $id").put("sender", owner).put("timestamp", at)

    private fun hiding(vararg ids: String) = JSONObject()
        .put("type", "ADMIN_STATE").put("rev", 1).put("ts", t0).put("createdBy", owner)
        .put("state", JSONObject()
            .put("bannedMembers", JSONArray())
            .put("hiddenMessageIds", JSONArray(ids.toList()))
            .put("pins", JSONArray()))

    private fun ids() = manager.messages.value.map { it.id }

    @Test
    fun `a failed read of the open is read again when the bridge comes back`() {
        content[roomA.messageStreamId] = listOf(text("m1", t0 - 2), text("m2", t0 - 1))
        failing = setOf(roomA.messageStreamId)

        manager.openChannel(roomA.messageStreamId)

        assertEquals(ChannelManager.HistoryRead.RETRYING, manager.historyRead.value)
        assertTrue(ids().isEmpty())

        failing = emptySet()
        manager.kickOpenReadRetry()

        assertEquals(ChannelManager.HistoryRead.OK, manager.historyRead.value)
        assertEquals(listOf("m1", "m2"), ids())
    }

    @Test
    fun `a read the storage node broke off is read again, keeping what came`() {
        content[roomA.messageStreamId] = listOf(text("m1", t0 - 2))
        brokenOff = setOf(roomA.messageStreamId)

        manager.openChannel(roomA.messageStreamId)

        assertEquals(ChannelManager.HistoryRead.RETRYING, manager.historyRead.value)
        assertEquals(listOf("m1"), ids())

        brokenOff = emptySet()
        content[roomA.messageStreamId] = listOf(text("m1", t0 - 2), text("m2", t0 - 1))
        manager.kickOpenReadRetry()

        assertEquals(ChannelManager.HistoryRead.OK, manager.historyRead.value)
        assertEquals(listOf("m1", "m2"), ids())
    }

    @Test
    fun `a 4xx from the storage node is its answer, not a read to retry`() {
        brokenOff = setOf(roomA.messageStreamId)
        nodeAnswer = JSONObject().put("status", 403).put("signed", true)

        manager.openChannel(roomA.messageStreamId)

        assertEquals(ChannelManager.HistoryRead.OK, manager.historyRead.value)
        assertEquals(403, manager.historyError.value?.status)
    }

    @Test
    fun `a page refused for the storedAt its node owes is a read to retry`() {
        brokenOff = setOf(roomA.messageStreamId)
        nodeAnswer = JSONObject().put("status", 503).put("reason", "storedAt")

        manager.openChannel(roomA.messageStreamId)

        assertEquals(ChannelManager.HistoryRead.RETRYING, manager.historyRead.value)
    }

    @Test
    fun `giving up on a broken-off read closes paging, so the screen can say so`() {
        brokenOff = setOf(roomA.messageStreamId)
        manager.openChannel(roomA.messageStreamId)

        repeat(manager.historyRetryDelaysMs.size) { manager.kickOpenReadRetry() }

        assertEquals(ChannelManager.HistoryRead.FAILED, manager.historyRead.value)
        assertEquals(false, manager.hasMoreHistory.value)
    }

    @Test
    fun `paging waits while the open's reads are owed, and after they gave up`() {
        brokenOff = setOf(roomA.messageStreamId)
        manager.openChannel(roomA.messageStreamId)

        assertEquals(ChannelManager.HistoryRead.RETRYING, manager.historyRead.value)
        assertTrue(manager.hasMoreHistory.value)
        assertEquals(0, runBlocking { manager.loadMoreHistory() })

        repeat(manager.historyRetryDelaysMs.size) { manager.kickOpenReadRetry() }
        assertEquals(ChannelManager.HistoryRead.FAILED, manager.historyRead.value)
        assertEquals(0, runBlocking { manager.loadMoreHistory() })

        coVerify(exactly = 0) { h.bridge.call("resendRange", any(), any()) }
    }

    @Test
    fun `a stream without storage is an answer, not a read to retry`() {
        content[roomA.messageStreamId] = listOf(text("m1", t0 - 1))
        unstored = setOf(StreamConstants.deriveInteractionsId(roomA.messageStreamId))

        manager.openChannel(roomA.messageStreamId)

        assertEquals(ChannelManager.HistoryRead.OK, manager.historyRead.value)
        assertEquals(listOf("m1"), ids())
    }

    @Test
    fun `a channel with no storage at all stays an empty channel`() {
        unstored = setOf(roomA.messageStreamId, StreamConstants.deriveInteractionsId(roomA.messageStreamId))

        manager.openChannel(roomA.messageStreamId)

        assertEquals(ChannelManager.HistoryRead.OK, manager.historyRead.value)
        assertTrue(ids().isEmpty())
    }

    @Test
    fun `reads that keep failing give up after the last attempt`() {
        failing = setOf(roomA.messageStreamId)
        manager.openChannel(roomA.messageStreamId)

        repeat(manager.historyRetryDelaysMs.size) { manager.kickOpenReadRetry() }

        assertEquals(ChannelManager.HistoryRead.FAILED, manager.historyRead.value)
    }

    @Test
    fun `a retry for a channel left behind applies nothing to the one on screen`() {
        content[roomA.messageStreamId] = listOf(text("a1", t0 - 1))
        content[roomB.messageStreamId] = listOf(text("b1", t0 - 1))
        failing = setOf(roomA.messageStreamId)
        manager.openChannel(roomA.messageStreamId)

        manager.openChannel(roomB.messageStreamId)
        failing = emptySet()
        manager.kickOpenReadRetry()

        assertEquals(listOf("b1"), ids())
        assertEquals(ChannelManager.HistoryRead.OK, manager.historyRead.value)
    }

    @Test
    fun `a retry over a repainted reopen merges by id and keeps the moderation`() {
        content[roomA.messageStreamId] = listOf(text("m1", t0 - 2), text("m2", t0 - 1))
        admin[roomA.adminStreamId] = hiding("m2")
        manager.openChannel(roomA.messageStreamId)
        assertTrue("m2" in manager.hiddenIds.value)
        manager.openChannel(roomB.messageStreamId)

        failing = setOf(roomA.messageStreamId)
        manager.openChannel(roomA.messageStreamId)
        assertEquals(ChannelManager.HistoryRead.RETRYING, manager.historyRead.value)
        failing = emptySet()
        manager.kickOpenReadRetry()

        assertEquals(listOf("m1", "m2"), ids())
        assertTrue("m2" in manager.hiddenIds.value)
        assertEquals(ChannelManager.HistoryRead.OK, manager.historyRead.value)
    }
}
