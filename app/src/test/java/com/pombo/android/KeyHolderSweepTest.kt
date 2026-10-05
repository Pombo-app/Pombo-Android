package com.pombo.android

import com.pombo.android.core.StreamConstants
import io.mockk.coEvery
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A moderator can re-admit and remove someone between two owner opens. No
 * sweep sees them with access, and if they were rotated for once they are
 * still covered. The -4 shows what matters: a KEY_REQUEST some responder
 * answered with the key in force. The owner's sweep rotates for those
 * holders once the gate cuts them, and for nobody who only asked or only
 * holds a key already rotated away.
 */
class KeyHolderSweepTest {

    private val me = com.pombo.android.core.EthereumSigner
        .address("0x4f3edf983ac636a65a842ce7c78d9aa706d3b113bce9c46f30d7d21715b23b1d").lowercase()
    private val gate = "0x" + "cd".repeat(20)
    private val holder = "0x" + "ab".repeat(20)
    private val responder = "0x" + "ef".repeat(20)
    private val streamId = "$me/gated-1"
    private val keysId = StreamConstants.deriveKeysId(streamId)

    private fun harness(covered: List<String> = emptyList()) = ChannelManagerHarness(channels = listOf(
        ChannelManagerHarness.channel(streamId, type = "gated")
            .copy(gateAddress = gate, accessSnapshot = listOf(me), rotatedForNoAccess = covered)
    ))

    private var h = harness()
    private val manager get() = h.manager
    private val keys get() = h.manager.epochKeys

    @After fun tearDown() = h.stop()

    private fun gateSays(access: Boolean) {
        coEvery { h.bridge.call("gateInfo", any()) } returns JSONObject().put("mode", 0)
        coEvery { h.bridge.call("gateMembers", any(), any()) } returns JSONObject().put("members", JSONArray()
            .put(JSONObject().put("address", me).put("isOwner", true).put("access", true))
            .put(JSONObject().put("address", holder).put("access", access)))
        coEvery { h.bridge.call("publishAsGate", any()) } returns JSONObject().put("timestamp", 1L)
    }

    /** Opens as the owner, which mints epoch 1; returns the key id in force. */
    private fun open(): String = runBlocking {
        manager.openChannel(streamId)
        keys.currentAnchorKeyIds(streamId).first()
    }

    private fun request(id: String, from: String = holder) = runBlocking {
        keys.handleKeysMessage(streamId, keysId,
            JSONObject().put("t", StreamConstants.KEY_REQUEST).put("requestId", id), from, 1_000L)
    }

    /** A wrap as any responder publishes it; who sent it is not what counts. */
    private fun wrap(requestId: String, keyId: String) = runBlocking {
        keys.handleKeysMessage(streamId, keysId,
            JSONObject().put("t", StreamConstants.KEY_WRAP).put("requestId", requestId).put("keyId", keyId),
            responder, 1_000L)
    }

    private fun holders() = runBlocking { keys.currentKeyHolders(streamId) }

    /** The owner's sweep; returns the epoch in force after it. */
    private fun sweep(): Int? = runBlocking {
        manager.rotateForLostAccess(manager.channels.value.single())
        keys.currentEpoch(streamId)
    }

    @Test fun `a wrap of the key in force from any responder makes the requester a holder`() {
        gateSays(access = true)
        val inForce = open()

        request("r1", holder.uppercase().replace("0X", "0x"))
        wrap("r1", inForce)

        assertEquals(listOf(holder), holders())
    }

    @Test fun `counts in either order`() {
        gateSays(access = true)
        val inForce = open()

        wrap("r1", inForce)
        request("r1")

        assertEquals(listOf(holder), holders())
    }

    @Test fun `a wrap with no matching request in the window does not count`() {
        gateSays(access = true)
        val inForce = open()

        request("r1")
        wrap("r2", inForce)

        assertTrue(holders().isEmpty())
    }

    @Test fun `rotates for a re-admitted member who took the key in force and was removed, though covered`() {
        h.stop()
        h = harness(covered = listOf(holder))
        gateSays(access = false)
        val inForce = open()
        request("r1")
        wrap("r1", inForce)

        assertEquals(2, sweep())
        assertEquals(listOf(holder), manager.channels.value.single().rotatedForNoAccess)
    }

    @Test fun `a covered member holding only a key already rotated away is left alone`() {
        h.stop()
        h = harness(covered = listOf(holder))
        gateSays(access = false)
        open()
        request("r1")
        wrap("r1", "0.rotated-away")

        assertEquals(1, sweep())
    }

    @Test fun `a request nobody answered is never rotated for`() {
        gateSays(access = false)
        open()
        request("r1")

        assertEquals(1, sweep())
        assertFalse(holder in manager.channels.value.single().rotatedForNoAccess)
    }

    @Test fun `a key holder the gate still admits is left alone`() {
        gateSays(access = true)
        val inForce = open()
        request("r1")
        wrap("r1", inForce)

        assertEquals(1, sweep())
    }

    @Test fun `on a warm open the sweep waits for the -4 read that names the key holders`() {
        gateSays(access = false)
        val inForce = open()
        request("r1")
        wrap("r1", inForce)

        runBlocking { manager.openChannel(streamId) }
        assertEquals(1, runBlocking { keys.currentEpoch(streamId) })

        val rotated = runBlocking {
            withTimeoutOrNull(30_000) {
                while (keys.currentEpoch(streamId) != 2) delay(250)
                true
            }
        }
        assertEquals(true, rotated)
    }

    /** Opens warm with a key holder cut, then lets the deferred read and sweep have their chance. */
    private fun warmOpenThen(interrupt: () -> Unit) {
        gateSays(access = false)
        val inForce = open()
        request("r1")
        wrap("r1", inForce)
        runBlocking { manager.openChannel(streamId) }

        interrupt()
        runBlocking { delay(12_000) }
    }

    @Test fun `switching channel during the deferred read keeps the sweep off the channel left`() {
        val otherId = "$me/gated-2"
        h.stop()
        h = ChannelManagerHarness(channels = listOf(
            ChannelManagerHarness.channel(streamId, type = "gated").copy(gateAddress = gate, accessSnapshot = listOf(me)),
            ChannelManagerHarness.channel(otherId, type = "gated").copy(gateAddress = "0x" + "ce".repeat(20))
        ))

        warmOpenThen { runBlocking { manager.openChannel(otherId) } }

        assertEquals(1, runBlocking { keys.currentEpoch(streamId) })
        assertTrue(manager.channels.value.single { it.messageStreamId == streamId }.rotatedForNoAccess.isEmpty())
    }

    @Test fun `closing the channel during the deferred read leaves its record alone`() {
        warmOpenThen { manager.closeCurrent() }

        assertEquals(1, runBlocking { keys.currentEpoch(streamId) })
        assertTrue(manager.channels.value.single().rotatedForNoAccess.isEmpty())
    }

    @Test fun `on a cold open the sweep runs as soon as the setup has read the -4`() {
        h.stop()
        h = ChannelManagerHarness(channels = listOf(
            ChannelManagerHarness.channel(streamId, type = "gated")
                .copy(gateAddress = gate, accessSnapshot = listOf(me, holder))
        ))
        gateSays(access = false)

        open()

        assertEquals(2, runBlocking { keys.currentEpoch(streamId) })
    }
}
