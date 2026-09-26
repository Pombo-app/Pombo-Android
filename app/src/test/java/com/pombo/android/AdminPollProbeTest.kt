package com.pombo.android

import com.pombo.android.core.SyncChunks
import com.pombo.android.data.Channel
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The poll of an open channel's moderation reads the newest -3 row first.
 * Only an owner's snapshot or manifest at a rev already held ends the poll
 * there; anything else reads the window exactly as before, so a probe can
 * save a read but never hide a change.
 */
class AdminPollProbeTest {

    private val me = com.pombo.android.core.EthereumSigner
        .address("0x4f3edf983ac636a65a842ce7c78d9aa706d3b113bce9c46f30d7d21715b23b1d").lowercase()
    private val streamId = "$me/room-1"
    private val gate = "0x" + "9a".repeat(20)
    private val stranger = "0x" + "cc".repeat(20)

    /** The -3 as the node holds it, oldest first: (row, publisher, signer). */
    private val storage = mutableListOf<Triple<JSONObject, String, String>>()
    /** The `last` of every -3 read, in order. */
    private val reads = mutableListOf<Int>()
    private var failProbe = false

    private lateinit var h: ChannelManagerHarness
    private lateinit var room: Channel

    private fun start(channel: Channel) {
        room = channel
        h = ChannelManagerHarness(channels = listOf(room))
        h.manager.adminConfirmSleep = { }
        every { h.manager.adminFloorStore.get(any()) } returns null
        coEvery { h.bridge.call("resolveStorageEndpoints", any()) } answers { JSONObject().put("nodes", JSONArray()) }
        coEvery { h.bridge.call("resend", any(), any()) } answers {
            val args = secondArg<JSONObject>()
            val messages = JSONArray()
            if (args.optString("streamId") == room.adminStreamId) {
                val last = args.optInt("last")
                reads += last
                if (last == 1 && failProbe) throw IllegalStateException("read failed")
                for ((row, publisher, signer) in storage.takeLast(last)) {
                    messages.put(JSONObject().put("content", row).put("meta", JSONObject()
                        .put("publisherId", publisher).put("signer", signer).put("timestamp", 1L)))
                }
            }
            JSONObject().put("messages", messages)
        }
        h.manager.openChannel(streamId)
        reads.clear()
    }

    @After fun tearDown() = h.stop()

    /** Snapshots are recent, or the owner's open republishes them as nearing their TTL. */
    private val t0 = System.currentTimeMillis()

    private fun snapshot(rev: Int, hidden: Int = 5) = JSONObject()
        .put("type", "ADMIN_STATE").put("rev", rev).put("ts", t0 + rev).put("createdBy", me)
        .put("state", JSONObject()
            .put("bannedMembers", JSONArray())
            .put("hiddenMessageIds", JSONArray((0 until hidden).map { "message-id-%030d".format(it) }))
            .put("pins", JSONArray()))

    private fun byOwner(row: JSONObject) = Triple(row, me, me)
    private fun poll() = runBlocking { h.manager.pollAdminState(room) }
    private fun public() = start(ChannelManagerHarness.channel(streamId))

    @Test
    fun `stops at the owner's snapshot at a rev already held`() {
        storage += byOwner(snapshot(3))
        public()

        poll()

        assertEquals(listOf(1), reads)
    }

    @Test
    fun `stops at the owner's manifest at a rev already held`() {
        storage += SyncChunks.splitFramed(snapshot(3, 40), "r1", SyncChunks.ADMIN, 800).map { byOwner(it) }
        public()
        assertEquals(40, h.manager.hiddenIds.value.size)

        poll()

        assertEquals(listOf(1), reads)
    }

    @Test
    fun `reads the window for a newer rev`() {
        storage += byOwner(snapshot(3))
        public()
        storage += byOwner(snapshot(4, 9))

        poll()

        assertEquals(listOf(1, 5), reads)
        assertEquals(9, h.manager.hiddenIds.value.size)
    }

    @Test
    fun `reads the window when the newest row is a chunk`() {
        storage += byOwner(snapshot(3))
        public()
        storage += SyncChunks.splitFramed(snapshot(4, 40), "r1", SyncChunks.ADMIN, 800).dropLast(1).map { byOwner(it) }

        poll()

        assertEquals(listOf(1, 5), reads)
    }

    @Test
    fun `reads the window when the newest row is not the owner's`() {
        storage += byOwner(snapshot(3))
        public()
        storage += Triple(snapshot(3), stranger, stranger)

        poll()

        assertEquals(listOf(1, 5), reads)
    }

    @Test
    fun `reads the window when the probe read fails`() {
        storage += byOwner(snapshot(3))
        public()
        failProbe = true

        poll()

        assertEquals(listOf(1, 5), reads)
    }

    @Test
    fun `reads the window on an old gated -3 where the clone published over the owner`() {
        storage += byOwner(snapshot(3))
        start(ChannelManagerHarness.channel(streamId, type = "gated").copy(gateAddress = gate))
        // The clone holds a publish grant there; what it carries is signed by someone else.
        storage += Triple(snapshot(3), gate, stranger)

        poll()

        assertEquals(listOf(1, 5), reads)
    }

    @Test
    fun `stops on an old gated -3 at a clone row the owner signed, at a rev already held`() {
        storage += byOwner(snapshot(3))
        start(ChannelManagerHarness.channel(streamId, type = "gated").copy(gateAddress = gate))
        storage += Triple(snapshot(3), gate, me)

        poll()

        assertEquals(listOf(1), reads)
    }
}
