package com.pombo.android

import com.pombo.android.core.StreamConstants
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A channel list change made on this device reaches the account's other
 * devices only if it schedules a sync push (web saveChannels → onChannelsSaved);
 * a list that arrived from a pull must not be pushed back out.
 */
class ChannelSyncSignalTest {

    private val h = ChannelManagerHarness(channels = listOf(ChannelManagerHarness.channel(KNOWN)))
    private val manager = h.manager
    private var pushes = 0

    @Before fun setUp() {
        manager.onLocalStateChanged = { pushes++ }
    }

    @After fun tearDown() = h.stop()

    @Test fun `joining a channel schedules a push`() = runBlocking {
        coEvery { h.bridge.call("checkPermissions", any()) } returns
            JSONObject().put("canPublish", true).put("canSubscribe", true)
        coEvery { h.bridge.call("getStreamInfo", any()) } returns JSONObject().put(
            "metadata", JSONObject().put("description", """{"a":"pombo","t":"public","n":"Open"}""")
        )

        manager.joinChannel(OPEN)

        assertEquals(1, pushes)
    }

    @Test fun `starting a DM schedules a push`() = runBlocking {
        coEvery { h.bridge.call("getPeerPublicKey", any()) } returns JSONObject().put("publicKey", "0x02aa")

        manager.startDm(PEER)

        assertEquals(1, pushes)
    }

    @Test fun `a local rename schedules one push`() {
        manager.setLocalChannelIdentity(KNOWN, "Renamed", null)

        assertEquals(1, pushes)
    }

    @Test fun `leaving a channel schedules a push after its tombstone is written`() {
        val events = mutableListOf<String>()
        every { h.store.markLeft(KNOWN, any()) } answers { events += "tombstone" }
        manager.onLocalStateChanged = { events += "push" }

        manager.removeChannel(KNOWN)

        assertEquals(listOf("tombstone", "push"), events)
    }

    @Test fun `adding a member to a gated channel schedules a push`() = runBlocking {
        val gated = ChannelManagerHarness.channel(GATED, type = "gated").copy(gateAddress = GATE)
        manager._channels.value = listOf(gated)
        manager._current.value = gated

        manager.addMember(PEER)

        assertEquals(1, pushes)
    }

    @Test fun `opening a channel whose record lacks derived ids repairs it without a push`() {
        coEvery { h.bridge.call("gateInfo", any()) } returns JSONObject().put("wireIdentityName", "visible")
        val gated = ChannelManagerHarness.channel(GATED, type = "gated")
            .copy(gateAddress = GATE, wireIdentity = "visible", keysStreamId = "")
        manager._channels.value = listOf(gated)

        manager.openChannel(GATED)

        assertEquals(StreamConstants.deriveKeysId(GATED), manager.channels.value.single().keysStreamId)
        assertEquals(0, pushes)
    }

    @Test fun `a list imported from a sync pull is not pushed back`() {
        manager.replaceChannels(
            listOf(ChannelManagerHarness.channel(KNOWN), ChannelManagerHarness.channel(OPEN))
        )

        assertEquals(0, pushes)
    }

    private companion object {
        const val KNOWN = "0xowner/known-1"
        const val OPEN = "0xowner/open-1"
        const val PEER = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8"
        const val GATED = "0xowner/gated-1"
        const val GATE = "0x7a3ee479b790578fb9ce885aa3356f79c4df0305"
    }
}
