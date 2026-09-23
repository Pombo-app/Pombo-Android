package com.pombo.android

import com.pombo.android.core.StreamConstants
import com.pombo.android.data.Channel
import com.pombo.android.data.KeyResponderEntry
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A channel record that lost its Sealed mode stops handing out the shared keys
 * and publishes the wrong way. The gate knows the mode; every device has to
 * put it back, and tell its other devices, or their copy wins it back.
 */
class GateAuthorityReconcileTest {

    private val lost = Channel(
        messageStreamId = STREAM,
        ephemeralStreamId = StreamConstants.deriveEphemeralId(STREAM),
        adminStreamId = StreamConstants.deriveAdminId(STREAM),
        keysStreamId = StreamConstants.deriveKeysId(STREAM),
        name = "Sealed",
        type = "gated",
        gateAddress = GATE,
        wireIdentity = null,
        joinedAt = 1_000L
    )

    private val h = ChannelManagerHarness(channels = listOf(lost))
    private val manager = h.manager
    private var pushes = 0

    @Before fun setUp() {
        coEvery { h.bridge.call("gateInfo", any()) } returns
            JSONObject().put("wireIdentityName", "sealed").put("readOnly", false)
        manager.onLocalStateChanged = { pushes++ }
    }

    @After fun tearDown() = h.stop()

    private fun stored() = manager.channels.value.single { it.messageStreamId == STREAM }

    @Test fun `a record without its mode takes the gate's and is synced`() {
        manager.reconcileAllGateAuthority()

        assertEquals("sealed", stored().wireIdentity)
        assertEquals(1, pushes)
    }

    @Test fun `a sync import that brings the old copy back is corrected without another read`() {
        manager.reconcileAllGateAuthority()
        manager.replaceChannels(listOf(lost))

        assertEquals("sealed", stored().wireIdentity)
        assertEquals(2, pushes)
        coVerify(exactly = 1) { h.bridge.call("gateInfo", any()) }
    }

    @Test fun `a record that already agrees is left alone`() {
        manager.replaceChannels(listOf(lost.copy(wireIdentity = "sealed")))

        assertEquals("sealed", stored().wireIdentity)
        assertEquals(0, pushes)
    }

    @Test fun `the key responder sweep settles the mode before it answers`() = runBlocking {
        manager.sweepKeyResponder(listOf(
            KeyResponderEntry(STREAM, StreamConstants.deriveKeysId(STREAM), GATE, tag = "")))

        assertEquals("sealed", stored().wireIdentity)
    }

    private companion object {
        const val STREAM = "0xowner/sealed-1"
        const val GATE = "0x7a3ee479b790578fb9ce885aa3356f79c4df0305"
    }
}
