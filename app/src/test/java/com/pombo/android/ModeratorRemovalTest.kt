package com.pombo.android

import com.pombo.android.core.StreamConstants
import com.pombo.android.core.channels.RotationRetry
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A moderator can take a member off a Closed gate, but only the owner
 * announces epochs: the rotation is left to the owner's next open, and the
 * moderator's device owes nothing that would hold back their own messages.
 */
class ModeratorRemovalTest {

    private val owner = "0x" + "ee".repeat(20)
    private val gate = "0x" + "cd".repeat(20)
    private val member = "0x" + "ab".repeat(20)
    private val gatedId = "$owner/gated-1"
    private val keysId = StreamConstants.deriveKeysId(gatedId)
    private val gated = ChannelManagerHarness.channel(gatedId, type = "gated")
        .copy(gateAddress = gate, members = listOf(member))

    /** The device's local store, shared by every session of these tests. */
    private val floor = mutableMapOf<String, JSONObject>()
    private val gatePublishes = mutableListOf<String>()

    private val h = session()
    private val sessions = mutableListOf(h)

    private fun session() = ChannelManagerHarness(channels = listOf(gated)).also { s ->
        every { s.adminFloorStore.get(any()) } answers { floor[firstArg()] }
        every { s.adminFloorStore.put(any(), any()) } answers { floor[firstArg()] = secondArg() }
        every { s.adminFloorStore.remove(any()) } answers { floor.remove(firstArg<String>()); Unit }
        coEvery { s.bridge.call("gateCheckAccess", any()) } returns JSONObject().put("access", true)
        coEvery { s.bridge.call("gateInfo", any()) } returns JSONObject().put("mode", 0)
        coEvery { s.bridge.call("publishAsGate", any()) } answers {
            gatePublishes += secondArg<JSONObject>().optString("streamId")
            JSONObject().put("timestamp", System.currentTimeMillis())
        }
    }

    @Before fun setUp() {
        h.manager.openChannel(gatedId)
    }

    @After fun tearDown() = sessions.forEach { it.stop() }

    private fun stored(m: ChannelManager = h.manager) = m.channels.value.single()

    /** Past the owed rotation, a member's send in this harness stops for want of an epoch key. */
    private fun sendError(): String? =
        runCatching { runBlocking { h.manager.sendMessage("after the removal") } }.exceptionOrNull()?.message

    @Test fun `a moderator's removal owes no rotation and holds none of their messages back`() {
        val rotated = runBlocking { h.manager.removeMember(member) }

        assertFalse(rotated)
        assertFalse(member in stored().members)
        assertTrue(floor.isEmpty())
        assertTrue(keysId !in gatePublishes)
        assertNotEquals(RotationRetry.OWED_MESSAGE, sendError())
    }

    @Test fun `a debt left on a channel this account does not own is dropped, not held against its sends`() {
        floor["rotation-owed|${h.me}|$gatedId"] = JSONObject().put("addresses", JSONArray().put(member))

        assertNotEquals(RotationRetry.OWED_MESSAGE, sendError())
        assertTrue(floor.isEmpty())
    }

    @Test fun `a debt left on a channel this account does not own is dropped when the next session connects`() {
        floor["rotation-owed|${h.me}|$gatedId"] = JSONObject().put("addresses", JSONArray().put(member))

        val next = session().also { sessions += it }
        next.manager.resumeOwedRotations()

        assertTrue(floor.isEmpty())
        assertTrue(keysId !in gatePublishes)
    }
}
