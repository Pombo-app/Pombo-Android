package com.pombo.android

import com.pombo.android.core.StreamConstants
import com.pombo.android.core.SyncChunks
import io.mockk.coEvery
import io.mockk.every
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
 * An ADMIN_STATE that no longer fits one wire message. Past the DataChannel's
 * max-message-size the SDK throws where nothing here sees it and the snapshot
 * is simply gone, so it is measured as it will travel: whole when it fits, a
 * run of chunks when it does not, refused past the cap. Readers put a run
 * back together from their own window, never from someone else's rows.
 */
class AdminStateSplitTest {

    private val me = com.pombo.android.core.EthereumSigner
        .address("0x4f3edf983ac636a65a842ce7c78d9aa706d3b113bce9c46f30d7d21715b23b1d").lowercase()
    private val streamId = "$me/room-1"
    private val room = ChannelManagerHarness.channel(streamId)
    private val h = ChannelManagerHarness(channels = listOf(room))
    private val manager = h.manager

    /** The -3 as the node holds it, oldest first: (row, publisher). */
    private val storage = mutableListOf<Pair<JSONObject, String>>()
    /** The `last` of every -3 read, in order. */
    private val reads = mutableListOf<Int>()

    @Before fun setUp() {
        manager.adminConfirmSleep = { }
        every { manager.adminFloorStore.get(any()) } returns null
        coEvery { h.bridge.call("resolveStorageEndpoints", any()) } answers {
            JSONObject().put("nodes", JSONArray())
        }
        coEvery { h.bridge.call("resend", any(), any()) } answers {
            val args = secondArg<JSONObject>()
            val messages = JSONArray()
            if (args.optString("streamId") == room.adminStreamId) {
                val last = args.optInt("last")
                println("DIAG resend -3 last=$last [${Thread.currentThread().name}] at ${Throwable().stackTrace.filter { it.className.startsWith("com.pombo.android") && !it.className.contains("Test") }.take(5).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }}")
                reads += last
                for ((row, publisher) in storage.takeLast(last)) {
                    messages.put(JSONObject().put("content", row)
                        .put("meta", JSONObject().put("publisherId", publisher).put("timestamp", 1L)))
                }
            }
            JSONObject().put("messages", messages)
        }
    }

    @After fun tearDown() = h.stop()

    private fun snapshot(rev: Int, hidden: Int) = JSONObject()
        .put("type", "ADMIN_STATE").put("rev", rev).put("ts", 1_000L * rev).put("createdBy", me)
        .put("state", JSONObject()
            .put("bannedMembers", JSONArray())
            .put("hiddenMessageIds", JSONArray((0 until hidden).map { "message-id-%030d".format(it) }))
            .put("pins", JSONArray()))

    /** Every -3 row this device handed the bridge. */
    private fun adminRows() = h.published.map { JSONObject(it) }
        .filter { it.optString("streamId") == room.adminStreamId }
        .map { it.opt("content") as JSONObject }

    private fun signals() = h.published.map { JSONObject(it) }
        .filter { it.optString("streamId") == room.ephemeralStreamId }
        .mapNotNull { it.optJSONObject("content") }
        .filter { it.optString("type") == "admin_invalidate" }

    private fun open() {
        manager.openChannel(streamId)
        println("DIAG test open: reads=$reads current=${manager._current.value?.adminStreamId} gen=${manager.switchGeneration} hidden=${manager.hiddenIds.value.size} [${Thread.currentThread().name}]")
        reads.clear()
    }

    private fun state() = "reads=$reads hidden=${manager.hiddenIds.value.size} current=${manager._current.value?.adminStreamId} gen=${manager.switchGeneration}"

    @Test
    fun `a snapshot that fits goes out whole, and rides the signal`() {
        storage += snapshot(1, 10) to me
        open()

        runBlocking { manager.hideMessage("fresh", true) }

        assertEquals(listOf("ADMIN_STATE"), adminRows().map { it.optString("type") })
        assertTrue(signals().single().has("snapshot"))
    }

    @Test
    fun `a snapshot past the budget goes out as a run, and the signal carries only its rev`() {
        storage += snapshot(1, 6_000) to me
        open()

        runBlocking { manager.hideMessage("fresh", true) }

        val rows = adminRows()
        assertTrue(rows.size > 2)
        assertTrue(rows.dropLast(1).all { it.optString("type") == "admin_chunk" })
        assertEquals("admin_manifest", rows.last().optString("type"))
        assertTrue(rows.all { it.toString().toByteArray().size <= ChannelManager.MAX_CHUNK_BYTES - 1024 })
        val joined = SyncChunks.joinFramed(rows, SyncChunks.ADMIN).single().payload
        assertEquals(2, joined.optInt("rev"))
        assertEquals(6_001, joined.getJSONObject("state").getJSONArray("hiddenMessageIds").length())
        val signal = signals().single()
        assertFalse(signal.has("snapshot"))
        assertEquals(2, signal.optInt("rev"))
    }

    @Test
    fun `past the cap nothing goes out and the change is rolled back`() {
        storage += snapshot(1, 25_000) to me
        open()

        val error = runCatching { runBlocking { manager.hideMessage("fresh", true) } }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("too large"))
        assertTrue(adminRows().isEmpty())
        assertTrue(signals().isEmpty())
        assertFalse("fresh" in manager.hiddenIds.value)
    }

    @Test
    fun `a run on storage is read back as the snapshot it carried`() {
        storage += snapshot(1, 5) to me
        storage += SyncChunks.splitFramed(snapshot(2, 40), "r1", SyncChunks.ADMIN, 800).map { it to me }

        open()

        assertEquals(40, manager.hiddenIds.value.size)
    }

    @Test
    fun `a run longer than the window is read again, wider`() {
        val run = SyncChunks.splitFramed(snapshot(2, 40), "r1", SyncChunks.ADMIN, 400)
        storage += snapshot(1, 5) to me
        storage += run.map { it to me }

        manager.openChannel(streamId)

        assertEquals(40, manager.hiddenIds.value.size)
        assertEquals(listOf(5, 5 + (run.size - 1) + 1), reads.take(2))
    }

    @Test
    fun `rows from another author never complete a run`() {
        storage += snapshot(1, 5) to me
        storage += SyncChunks.splitFramed(snapshot(2, 40), "r1", SyncChunks.ADMIN, 800)
            .mapIndexed { i, row -> row to if (i == 0) "0x" + "cc".repeat(20) else me }

        open()

        assertEquals(5, manager.hiddenIds.value.size)
    }

    @Test
    fun `a signal without the snapshot makes a member read the -3`() {
        storage += snapshot(1, 5) to me
        open()
        storage += SyncChunks.splitFramed(snapshot(2, 40), "r1", SyncChunks.ADMIN, 800).map { it to me }

        println("DIAG before deliver: ${state()}")
        h.deliver(room.ephemeralStreamId, StreamConstants.EPH_CONTROL,
            JSONObject().put("type", "admin_invalidate").put("rev", 2).put("ts", 2_000L), from = me)
        println("DIAG after deliver: ${state()}")

        assertTrue(state(), reads.isNotEmpty())
        assertEquals(state(), 40, manager.hiddenIds.value.size)
    }

    @Test
    fun `a -3 row is sized as the bridge is handed it`() = runBlocking {
        val row = snapshot(1, 20)
        val measured = manager.wireBytes(room, room.adminStreamId, row)
        manager.publishForChannel(room, room.adminStreamId, StreamConstants.ADMIN_MODERATION, row)

        val handed = JSONObject(h.published.last()).get("content")
        assertEquals(handed.toString().toByteArray().size, measured)
    }

    @Test
    fun `a password-sealed -3 row is sized as the ciphertext the bridge is handed`() = runBlocking {
        io.mockk.mockkStatic(android.util.Base64::class)
        every { android.util.Base64.encodeToString(any<ByteArray>(), any()) } answers {
            java.util.Base64.getEncoder().encodeToString(firstArg<ByteArray>())
        }
        try {
            val sealed = room.copy(type = "password", password = "pw")
            val row = snapshot(1, 20)
            val measured = manager.wireBytes(sealed, sealed.adminStreamId, row)
            manager.publishForChannel(sealed, sealed.adminStreamId, StreamConstants.ADMIN_MODERATION, row)

            val handed = JSONObject(h.published.last()).getString("content")
            assertEquals(JSONObject.quote(handed).toByteArray().size, measured)
        } finally {
            io.mockk.unmockkStatic(android.util.Base64::class)
        }
    }

    @Test
    fun `a -2 signal is sized with the proof the bridge adds`() = runBlocking {
        val signal = JSONObject().put("type", "admin_invalidate").put("rev", 1).put("ts", 1L)
            .put("snapshot", snapshot(1, 20))
        val measured = manager.wireBytes(room, room.ephemeralStreamId, signal)
        manager.publishForChannel(room, room.ephemeralStreamId, StreamConstants.EPH_CONTROL, signal)

        val args = JSONObject(h.published.last())
        val wire = JSONObject(args.getJSONObject("content").toString())
            .put("proof", args.getJSONObject("identity").getString("proof"))
        assertEquals(wire.toString().toByteArray().size, measured)
    }

    @Test
    fun `a run that lands on the first read is not read again`() {
        storage += snapshot(1, 5) to me
        open()
        storage += SyncChunks.splitFramed(snapshot(2, 40), "r1", SyncChunks.ADMIN, 800).map { it to me }

        println("DIAG before deliver: ${state()}")
        h.deliver(room.ephemeralStreamId, StreamConstants.EPH_CONTROL,
            JSONObject().put("type", "admin_invalidate").put("rev", 2).put("ts", 2_000L), from = me)
        println("DIAG after deliver: ${state()}")

        assertEquals(state(), 1, reads.size)
    }

    @Test
    fun `a run not on storage yet is read once more, and no more`() {
        storage += snapshot(1, 5) to me
        open()

        println("DIAG before deliver: ${state()}")
        h.deliver(room.ephemeralStreamId, StreamConstants.EPH_CONTROL,
            JSONObject().put("type", "admin_invalidate").put("rev", 2).put("ts", 2_000L), from = me)
        println("DIAG after deliver: ${state()}")

        assertEquals(state(), 2, reads.size)
        assertEquals(state(), 5, manager.hiddenIds.value.size)
    }

    @Test
    fun `a signal for a rev already held reads nothing`() {
        storage += snapshot(3, 5) to me
        open()

        h.deliver(room.ephemeralStreamId, StreamConstants.EPH_CONTROL,
            JSONObject().put("type", "admin_invalidate").put("rev", 3).put("ts", 3_000L), from = me)

        assertTrue(reads.isEmpty())
    }
}
