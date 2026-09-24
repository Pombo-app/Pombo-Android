package com.pombo.android

import com.pombo.android.core.StreamConstants
import com.pombo.android.core.channels.RotationRetry
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A ban cuts the member on the gate, but they keep reading until the epoch
 * rotates. When the announce cannot go out, the rotation stays owed on this
 * device, the owner's own messages wait for it, and the next session takes
 * it up as soon as the bridge connects.
 */
class BanRotationTest {

    private val me = com.pombo.android.core.EthereumSigner
        .address("0x4f3edf983ac636a65a842ce7c78d9aa706d3b113bce9c46f30d7d21715b23b1d").lowercase()
    private val gate = "0x" + "cd".repeat(20)
    private val member = "0x" + "ab".repeat(20)
    private val gatedId = "$me/gated-1"
    private val keysId = StreamConstants.deriveKeysId(gatedId)
    private val gated = ChannelManagerHarness.channel(gatedId, type = "gated")
        .copy(gateAddress = gate, members = listOf(member))

    /** The device's local store, shared by every session of these tests. */
    private val floor = mutableMapOf<String, JSONObject>()
    /** Stream of every publish that went out through the gate, in order. */
    private val gatePublishes = mutableListOf<String>()
    private var meshUp = false

    private val h = session()
    private val manager = h.manager
    private val sessions = mutableListOf(h)

    private fun session() = ChannelManagerHarness(channels = listOf(gated)).also { s ->
        every { s.adminFloorStore.get(any()) } answers { floor[firstArg()] }
        every { s.adminFloorStore.put(any(), any()) } answers { floor[firstArg()] = secondArg() }
        every { s.adminFloorStore.remove(any()) } answers { floor.remove(firstArg<String>()); Unit }
        coEvery { s.bridge.call("gateCheckAccess", any()) } returns JSONObject().put("access", true)
        coEvery { s.bridge.call("publishAsGate", any()) } answers {
            if (!meshUp) throw IllegalStateException("Failed to connect to the entrypoints after 7 attempts")
            gatePublishes += secondArg<JSONObject>().optString("streamId")
            JSONObject().put("timestamp", System.currentTimeMillis())
        }
    }

    @Before fun setUp() {
        manager.openChannel(gatedId)
    }

    @After fun tearDown() = sessions.forEach { it.stop() }

    private fun stored(m: ChannelManager = manager) = m.channels.value.single()

    @Test fun `a ban whose rotation cannot go out stays owed and holds the owner's messages back`() {
        val rotated = runBlocking { manager.banMemberLevels(member, client = false, protocol = true) }

        assertFalse(rotated)
        assertTrue(member in stored().knownBanned)
        assertFalse(member in stored().members)
        assertFalse(member in stored().rotatedForNoAccess)

        val error = runCatching { runBlocking { manager.sendMessage("after the ban") } }.exceptionOrNull()

        assertEquals(RotationRetry.OWED_MESSAGE, error?.message)
        assertTrue(manager.messages.value.single().failed)
        assertTrue(gatedId !in gatePublishes)
    }

    @Test fun `once the mesh is back the owner's retry rotates before it publishes`() {
        runBlocking { manager.banMemberLevels(member, client = false, protocol = true) }
        runCatching { runBlocking { manager.sendMessage("after the ban") } }
        val id = manager.messages.value.single().id

        meshUp = true
        runBlocking { manager.resendMessage(id) }

        assertTrue(member in stored().rotatedForNoAccess)
        assertFalse(manager.messages.value.single().failed)
        val announce = gatePublishes.indexOf(keysId)
        assertTrue(announce >= 0)
        assertTrue(announce < gatePublishes.indexOf(gatedId))
    }

    @Test fun `the next session rotates what the last one owed as soon as the bridge connects`() {
        runBlocking { manager.banMemberLevels(member, client = false, protocol = true) }

        meshUp = true
        val next = session().also { sessions += it }
        next.manager.resumeOwedRotations()

        assertTrue(keysId in gatePublishes)
        assertTrue(member in stored(next.manager).rotatedForNoAccess)
        assertTrue(floor.isEmpty())
    }

    @Test fun `the owner's sweep leaves an owed rotation to the retry`() {
        runBlocking { manager.banMemberLevels(member, client = false, protocol = true) }
        var gateReads = 0
        coEvery { h.bridge.call("gateMembers", any(), any()) } answers { gateReads++; JSONObject() }

        runBlocking { manager.rotateForLostAccess(stored()) }

        assertEquals(0, gateReads)
    }

    @Test fun `a removal from a Closed gate whose rotation cannot go out stays owed`() {
        coEvery { h.bridge.call("gateInfo", any()) } returns JSONObject().put("mode", 0)

        val rotated = runBlocking { manager.removeMember(member) }

        assertFalse(rotated)
        assertFalse(member in stored().members)
        val error = runCatching { runBlocking { manager.sendMessage("after the removal") } }.exceptionOrNull()
        assertEquals(RotationRetry.OWED_MESSAGE, error?.message)

        meshUp = true
        val nextSession = next()
        nextSession.resumeOwedRotations()
        assertTrue(member in stored(nextSession).rotatedForNoAccess)
    }

    @Test fun `an image waits for the owed rotation as a text does`() {
        runBlocking { manager.banMemberLevels(member, client = false, protocol = true) }

        val error = runCatching { runBlocking { manager.sendImage(ByteArray(8), "image/jpeg") } }.exceptionOrNull()

        assertEquals(RotationRetry.OWED_MESSAGE, error?.message)
        assertTrue(manager.messages.value.isEmpty())
    }

    /** Another session of the same device, sharing its local store. */
    private fun next(): ChannelManager = session().also { sessions += it }.manager

    @Test fun `a ban whose rotation goes out is covered at once`() {
        meshUp = true

        val rotated = runBlocking { manager.banMemberLevels(member, client = false, protocol = true) }

        assertTrue(rotated)
        assertTrue(member in stored().knownBanned)
        assertTrue(member in stored().rotatedForNoAccess)
        assertTrue(floor.isEmpty())
    }
}
