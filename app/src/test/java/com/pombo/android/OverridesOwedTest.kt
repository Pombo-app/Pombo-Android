package com.pombo.android

import com.pombo.android.core.StreamConstants
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Edits and deletions live on P1, the messages on P0. A P1 that did not come
 * back whole is owed, not empty: the open reads it again and says so
 * meanwhile, and an older page is only taken with both halves.
 */
class OverridesOwedTest {

    private val owner = "0x" + "cd".repeat(20)
    private val room = ChannelManagerHarness.channel("$owner/room-1")
    private val h = ChannelManagerHarness(channels = listOf(room))
    private val manager = h.manager
    private val t0 = System.currentTimeMillis()

    /** How a read answers: whole, not at all, cut by its budget, broken off, or refused by the node. */
    private enum class Answer { WHOLE, THROWS, CUT, BROKEN, REFUSED }

    @Volatile private var openP1 = Answer.WHOLE
    private var content = listOf<JSONObject>()
    private var overrides = listOf<JSONObject>()
    /** Holds the open's P0 read until completed, when set. */
    @Volatile private var p0Gate: CompletableDeferred<Unit>? = null

    @Volatile private var olderP0 = Answer.WHOLE
    @Volatile private var olderP1 = Answer.WHOLE
    private var olderContent = listOf<JSONObject>()
    private var olderOverrides = listOf<JSONObject>()

    @Before fun setUp() {
        manager.adminConfirmSleep = { }
        manager.historyRetryDelaysMs = longArrayOf(600_000L, 600_000L, 600_000L, 600_000L)
        manager.pagingRetryDelaysMs = longArrayOf(600_000L, 600_000L, 600_000L, 600_000L)
        every { manager.adminFloorStore.get(any()) } returns null
        coEvery { h.bridge.call("resend", any(), any()) } coAnswers {
            val args = secondArg<JSONObject>()
            if (args.optString("streamId") != room.messageStreamId) {
                page(emptyList())
            } else when (args.optInt("partition")) {
                StreamConstants.P_MESSAGES -> { p0Gate?.await(); page(content) }
                StreamConstants.P_CONTROL -> answer(overrides, openP1)
                else -> page(emptyList())
            }
        }
        coEvery { h.bridge.call("resendRange", any(), any()) } answers {
            val args = secondArg<JSONObject>()
            if (args.optString("streamId") != room.messageStreamId) {
                page(emptyList()).put("hasMore", false)
            } else when (args.optInt("partition")) {
                StreamConstants.P_MESSAGES -> answer(olderContent, olderP0).put("hasMore", true)
                StreamConstants.P_CONTROL -> answer(olderOverrides, olderP1).put("hasMore", false)
                else -> page(emptyList()).put("hasMore", false)
            }
        }
    }

    @After fun tearDown() = h.stop()

    private fun page(rows: List<JSONObject>) = JSONObject().put("messages", JSONArray(rows.map { row ->
        JSONObject().put("content", row)
            .put("meta", JSONObject().put("publisherId", owner).put("timestamp", row.optLong("timestamp", t0)))
    }))

    private fun answer(rows: List<JSONObject>, how: Answer): JSONObject = when (how) {
        Answer.WHOLE -> page(rows)
        Answer.THROWS -> throw IllegalStateException("Timed out waiting for 60000 ms")
        Answer.CUT -> page(emptyList()).put("partial", true)
        Answer.BROKEN -> page(emptyList()).put("partial", true).put("errors", 1)
        Answer.REFUSED -> page(emptyList()).put("partial", true).put("errors", 1)
            .put("readError", JSONObject().put("status", 403).put("signed", true))
    }

    private fun text(id: String, at: Long) = JSONObject().put("type", "text").put("id", id)
        .put("text", "hello $id").put("sender", owner).put("timestamp", at)

    private fun delete(targetId: String, at: Long) = JSONObject().put("type", "delete")
        .put("targetId", targetId).put("timestamp", at)

    private fun ids() = manager.messages.value.map { it.id }.toSet()

    @Test
    fun `a P1 that timed out is owed, and the read that brings it deletes what it should`() {
        content = listOf(text("m1", t0 - 3), text("m2", t0 - 2))
        overrides = listOf(delete("m2", t0 - 1))
        openP1 = Answer.THROWS

        manager.openChannel(room.messageStreamId)

        assertEquals(ChannelManager.HistoryRead.RETRYING, manager.historyRead.value)
        assertTrue(manager.overridesOwed.value)
        assertEquals(setOf("m1", "m2"), ids())

        openP1 = Answer.WHOLE
        manager.kickOpenReadRetry()

        assertEquals(ChannelManager.HistoryRead.OK, manager.historyRead.value)
        assertFalse(manager.overridesOwed.value)
        assertEquals(setOf("m1"), ids())
    }

    @Test
    fun `a P1 cut by its budget is owed, not an answer`() {
        content = listOf(text("m1", t0 - 1))
        openP1 = Answer.CUT

        manager.openChannel(room.messageStreamId)

        assertEquals(ChannelManager.HistoryRead.RETRYING, manager.historyRead.value)
        assertTrue(manager.overridesOwed.value)
    }

    @Test
    fun `a P1 the node refused is its answer`() {
        content = listOf(text("m1", t0 - 1))
        openP1 = Answer.REFUSED

        manager.openChannel(room.messageStreamId)

        assertEquals(ChannelManager.HistoryRead.OK, manager.historyRead.value)
        assertFalse(manager.overridesOwed.value)
    }

    @Test
    fun `giving up leaves the edits and deletions owed`() {
        content = listOf(text("m1", t0 - 1))
        openP1 = Answer.THROWS
        manager.openChannel(room.messageStreamId)

        repeat(manager.historyRetryDelaysMs.size) { manager.kickOpenReadRetry() }

        assertEquals(ChannelManager.HistoryRead.FAILED, manager.historyRead.value)
        assertTrue(manager.overridesOwed.value)
    }

    @Test
    fun `while the open's read is out the channel is reading, and paging waits`() {
        content = listOf(text("m1", t0 - 1))
        val gate = CompletableDeferred<Unit>().also { p0Gate = it }

        manager.openChannel(room.messageStreamId)

        assertEquals(ChannelManager.HistoryRead.READING, manager.historyRead.value)
        assertEquals(0, runBlocking { manager.loadMoreHistory() })
        coVerify(exactly = 0) { h.bridge.call("resendRange", any(), any()) }

        gate.complete(Unit)

        assertEquals(ChannelManager.HistoryRead.OK, manager.historyRead.value)
        assertEquals(setOf("m1"), ids())
    }

    /** Opens with m3 and m4, both halves back, so the cursor sits before them. */
    private fun openWithRecentPage() {
        content = listOf(text("m3", t0 - 3), text("m4", t0 - 2))
        olderContent = listOf(text("m1", t0 - 20), text("m2", t0 - 10))
        manager.openChannel(room.messageStreamId)
        assertEquals(ChannelManager.HistoryRead.OK, manager.historyRead.value)
        assertTrue(manager.hasMoreHistory.value)
    }

    /** The page is left where it was, and the next ask within the backoff reads nothing. */
    private fun assertOlderPageNotTaken() {
        assertEquals(0, runBlocking { manager.loadMoreHistory() })
        assertEquals(setOf("m3", "m4"), ids())
        assertTrue(manager.hasMoreHistory.value)

        assertEquals(0, runBlocking { manager.loadMoreHistory() })
        coVerify(exactly = 1) {
            h.bridge.call("resendRange", match {
                it.optString("streamId") == room.messageStreamId && it.optInt("partition") == StreamConstants.P_CONTROL
            }, any())
        }
    }

    @Test
    fun `an older page is taken with its overrides`() {
        openWithRecentPage()
        olderOverrides = listOf(delete("m1", t0 - 15))

        runBlocking { manager.loadMoreHistory() }

        assertEquals(setOf("m2", "m3", "m4"), ids())
    }

    @Test
    fun `an older page whose P1 timed out is not taken`() {
        openWithRecentPage()
        olderP1 = Answer.THROWS
        assertOlderPageNotTaken()
    }

    @Test
    fun `an older page whose P1 was cut by its budget is not taken`() {
        openWithRecentPage()
        olderP1 = Answer.CUT
        assertOlderPageNotTaken()
    }

    @Test
    fun `an older page whose P1 broke off is not taken`() {
        openWithRecentPage()
        olderP1 = Answer.BROKEN
        assertOlderPageNotTaken()
    }

    @Test
    fun `an older page whose P0 broke off is not taken`() {
        openWithRecentPage()
        olderP0 = Answer.BROKEN
        assertOlderPageNotTaken()
    }

    @Test
    fun `an older P1 the node refused is its answer, and the page is taken`() {
        openWithRecentPage()
        olderP1 = Answer.REFUSED

        runBlocking { manager.loadMoreHistory() }

        assertEquals(setOf("m1", "m2", "m3", "m4"), ids())
    }

    @Test
    fun `an incomplete older page is asked for again after each backoff, then left to the user`() {
        manager.pagingRetryDelaysMs = longArrayOf(0L, 0L)
        openWithRecentPage()
        olderP1 = Answer.THROWS

        repeat(3) { runBlocking { manager.loadMoreHistory() } }

        assertEquals(2, manager.pagingNudge.value)
    }
}
