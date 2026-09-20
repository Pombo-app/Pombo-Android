package com.pombo.android

import com.pombo.android.ChannelManager.PaidStatus
import com.pombo.android.ChannelManager.SubscriptionState
import com.pombo.android.data.Channel
import io.mockk.coEvery
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A member who never paid is not a member whose subscription lapsed, and an
 * owner or moderator owes nothing at all. Telling them apart is what decides
 * the strip, the empty timeline and the composer.
 */
class PaidSubscriptionStateTest {

    private val h = ChannelManagerHarness(channels = listOf(paidChannel()))
    private val manager = h.manager

    @After fun tearDown() = h.stop()

    private fun secondsFromNow(seconds: Long) = System.currentTimeMillis() / 1000 + seconds

    private fun gateSays(paidUntil: Long, owner: Boolean = false, moderator: Boolean = false) {
        coEvery { h.bridge.call("gateInfo", any()) } returns
            JSONObject().put("mode", 3).put("owner", GATE_OWNER)
        coEvery { h.bridge.call("gateMembers", any(), any()) } returns JSONObject()
            .put("members", JSONArray().put(JSONObject()
                .put("address", h.me)
                .put("isOwner", owner)
                .put("moderator", moderator)
                .put("paidUntil", paidUntil)))
    }

    private suspend fun stateNow(): SubscriptionState {
        manager.openChannel(STREAM)
        manager.refreshPaidStatus()
        return manager.paidStatus.value?.state ?: SubscriptionState.NONE
    }

    @Test fun `never paid reads as unsubscribed`() = runBlocking {
        gateSays(paidUntil = 0)
        assertEquals(SubscriptionState.UNSUBSCRIBED, stateNow())
    }

    @Test fun `an elapsed subscription reads as expired`() = runBlocking {
        gateSays(paidUntil = secondsFromNow(-60))
        assertEquals(SubscriptionState.EXPIRED, stateNow())
    }

    @Test fun `a live subscription reads as active`() = runBlocking {
        gateSays(paidUntil = secondsFromNow(3600))
        assertEquals(SubscriptionState.ACTIVE, stateNow())
    }

    @Test fun `a moderator owes no subscription`() = runBlocking {
        gateSays(paidUntil = 0, moderator = true)
        assertEquals(SubscriptionState.NONE, stateNow())
    }

    @Test fun `the owner owes no subscription`() = runBlocking {
        gateSays(paidUntil = 0, owner = true)
        assertEquals(SubscriptionState.NONE, stateNow())
    }

    @Test fun `the state is read from the clock, not from a stale access verdict`() {
        assertEquals(SubscriptionState.EXPIRED,
            PaidStatus(paidUntil = System.currentTimeMillis() / 1000 - 1, exempt = false).state)
    }

    companion object {
        private const val GATE_OWNER = "0x03e2b466754f187f571ab48c69e3ab592e76d819"
        private const val STREAM = "0xowner/paid-1"

        private fun paidChannel() = Channel(
            messageStreamId = STREAM,
            ephemeralStreamId = "0xowner/paid-2",
            adminStreamId = "0xowner/paid-3",
            name = "PaidChannel",
            type = "gated",
            readOnly = false,
            gateAddress = "0x8d9155513d5a96ed6880717217c5e0d466c3db49"
        )
    }
}
