package com.pombo.android

import com.pombo.android.core.StreamConstants
import io.mockk.coEvery
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A gated channel's history is read again after a renewal. The refusal the
 * storage node gave while access had lapsed used to outlive the payment: the
 * timeline kept saying the history was unavailable until the channel was
 * reopened.
 */
class HistoryAfterRenewalTest {

    private val owner = "0x7ea24eb97d400a76f8d96be92c4e7fce576aedb9"
    private val gate = "0x" + "cd".repeat(20)
    private val gatedId = "$owner/gated-1"
    private val h = ChannelManagerHarness(channels = listOf(
        ChannelManagerHarness.channel(gatedId, type = "gated").copy(gateAddress = gate)
    ))
    private val manager = h.manager

    @Volatile private var refused = true
    @Volatile private var contentReads = 0

    @Before fun setUp() {
        coEvery { h.bridge.call(eq("resend"), any(), any()) } answers {
            val args = secondArg<JSONObject>()
            val res = JSONObject().put("messages", JSONArray())
            if (args.optString("streamId") == gatedId && args.optInt("partition") == StreamConstants.P_MESSAGES) {
                contentReads++
                if (refused) res.put("readError", JSONObject().put("status", 403).put("signed", true))
            }
            res
        }
    }

    @After fun tearDown() = h.stop()

    @Test
    fun `a renewal re-reads the history the node refused and reopens paging`() = runBlocking {
        manager.openChannel(gatedId)
        assertEquals(403, manager.historyError.value?.status)
        assertFalse(manager.hasMoreHistory.value)

        refused = false
        manager.refreshHistoryAfterRenewal()
        delay(SETTLE_MS)

        assertNull(manager.historyError.value)
        assertTrue(manager.hasMoreHistory.value)
    }

    @Test
    fun `a renewal the node still refuses keeps the refusal`() = runBlocking {
        manager.openChannel(gatedId)
        val before = contentReads

        manager.refreshHistoryAfterRenewal()
        delay(SETTLE_MS)

        assertEquals(before + 1, contentReads)
        assertEquals(403, manager.historyError.value?.status)
        assertFalse(manager.hasMoreHistory.value)
    }

    private companion object {
        /** Past the refresh's debounce. */
        const val SETTLE_MS = 2_500L
    }
}
