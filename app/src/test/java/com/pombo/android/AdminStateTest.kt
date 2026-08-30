package com.pombo.android

import com.pombo.android.core.StreamConstants
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ADMIN_STATE: the moderation snapshot that carries pins, hidden messages and
 * bans. It arrives on the ephemeral stream wrapped in an `admin_invalidate`
 * signal, which is the low-latency path — there is no live subscription to the
 * admin stream itself.
 *
 * This is an authority boundary, not just a codec. A snapshot decides what the
 * whole channel hides and who it bans, so the checks that reject one are the
 * feature: owner-authored only, and latest-wins by (rev, ts) so a replayed
 * older snapshot cannot undo a newer one.
 */
class AdminStateTest {

    private val owner = "0xowner"
    private val streamId = "$owner/room-1"
    private val room = ChannelManagerHarness.channel(streamId)
    private val h = ChannelManagerHarness(channels = listOf(room))
    private val manager = h.manager
    private val stranger = "0xstranger"

    @Before fun setUp() = manager.openChannel(streamId)
    @After fun tearDown() = h.stop()

    private fun snapshot(
        rev: Int,
        ts: Long,
        pins: List<String> = emptyList(),
        hidden: List<String> = emptyList(),
        banned: List<String> = emptyList(),
        type: String = "ADMIN_STATE"
    ) = JSONObject()
        .put("type", type).put("rev", rev).put("ts", ts)
        .put("state", JSONObject()
            .put("hiddenMessageIds", JSONArray(hidden))
            .put("bannedMembers", JSONArray(banned))
            .put("pins", JSONArray(pins.map { id ->
                JSONObject().put("targetId", id).put("pinnedAt", ts)
                    .put("snapshot", JSONObject().put("sender", owner).put("text", "pinned $id"))
            })))

    /** Delivers the snapshot the way publishAdminState wraps it. */
    private fun deliver(snapshot: JSONObject, from: String = owner) =
        h.deliver(
            room.ephemeralStreamId, StreamConstants.EPH_CONTROL,
            JSONObject().put("type", "admin_invalidate")
                .put("rev", snapshot.optInt("rev")).put("ts", snapshot.optLong("ts"))
                .put("snapshot", snapshot),
            from
        )

    @Test
    fun `the owner's snapshot applies pins, hidden ids and bans`() {
        deliver(snapshot(rev = 1, ts = 100, pins = listOf("m1"), hidden = listOf("m2"), banned = listOf("0xBAD")))
        assertEquals(listOf("m1"), manager.pins.value.map { it.targetId })
        assertEquals(setOf("m2"), manager.hiddenIds.value)
        assertEquals(setOf("0xbad"), manager.bannedMembers.value)
    }

    /** Bans are compared lowercase everywhere; a mixed-case list must not slip through. */
    @Test
    fun `banned members are lowercased`() {
        deliver(snapshot(rev = 1, ts = 100, banned = listOf("0xAbCdEf")))
        assertEquals(setOf("0xabcdef"), manager.bannedMembers.value)
    }

    @Test
    fun `a snapshot from anyone but the owner is refused`() {
        deliver(snapshot(rev = 1, ts = 100, hidden = listOf("m1")), from = stranger)
        assertTrue(manager.hiddenIds.value.isEmpty())
    }

    @Test
    fun `a stale revision cannot undo a newer one`() {
        deliver(snapshot(rev = 5, ts = 500, hidden = listOf("m5")))
        deliver(snapshot(rev = 4, ts = 400, hidden = emptyList()))
        assertEquals(setOf("m5"), manager.hiddenIds.value)
    }

    /** The timestamp breaks rev ties, so a stale replica sharing a rev loses. */
    @Test
    fun `at the same revision the older timestamp loses`() {
        deliver(snapshot(rev = 5, ts = 500, hidden = listOf("m5")))
        deliver(snapshot(rev = 5, ts = 499, hidden = listOf("other")))
        assertEquals(setOf("m5"), manager.hiddenIds.value)
    }

    @Test
    fun `at the same revision a newer timestamp wins`() {
        deliver(snapshot(rev = 5, ts = 500, hidden = listOf("m5")))
        deliver(snapshot(rev = 5, ts = 501, hidden = listOf("m6")))
        assertEquals(setOf("m6"), manager.hiddenIds.value)
    }

    @Test
    fun `a higher revision wins`() {
        deliver(snapshot(rev = 5, ts = 500, hidden = listOf("m5")))
        deliver(snapshot(rev = 6, ts = 1, hidden = listOf("m6")))
        assertEquals(setOf("m6"), manager.hiddenIds.value)
    }

    @Test
    fun `a payload that is not an ADMIN_STATE is ignored`() {
        deliver(snapshot(rev = 1, ts = 100, hidden = listOf("m1"), type = "SOMETHING_ELSE"))
        assertTrue(manager.hiddenIds.value.isEmpty())
    }

    @Test
    fun `a snapshot with no state changes nothing`() {
        deliver(snapshot(rev = 1, ts = 100, hidden = listOf("m1")))
        h.deliver(
            room.ephemeralStreamId, StreamConstants.EPH_CONTROL,
            JSONObject().put("type", "admin_invalidate")
                .put("snapshot", JSONObject().put("type", "ADMIN_STATE").put("rev", 2).put("ts", 200)),
            owner
        )
        assertEquals(setOf("m1"), manager.hiddenIds.value)
    }

    /** pinnedAt is copied from the snapshot, never regenerated on receipt. */
    @Test
    fun `a pin keeps the timestamp the owner gave it`() {
        deliver(snapshot(rev = 1, ts = 100, pins = listOf("m1")))
        assertEquals(100L, manager.pins.value.single().pinnedAt)
        assertEquals("pinned m1", manager.pins.value.single().text)
    }

    @Test
    fun `an admin_invalidate with no snapshot is ignored`() {
        deliver(snapshot(rev = 1, ts = 100, hidden = listOf("m1")))
        h.deliver(
            room.ephemeralStreamId, StreamConstants.EPH_CONTROL,
            JSONObject().put("type", "admin_invalidate"), owner
        )
        assertEquals(setOf("m1"), manager.hiddenIds.value)
    }
}
