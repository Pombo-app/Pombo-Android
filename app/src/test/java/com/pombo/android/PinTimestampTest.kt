package com.pombo.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ADMIN_STATE `pins` codec: `pinnedAt` has to survive a republish untouched. */
class PinTimestampTest {

    private fun pin(id: String, at: Long) = ChannelManager.Pin(
        targetId = id, text = "hello $id", sender = "0xabc",
        senderName = "Alice", ensName = null, pinnedAt = at
    )

    @Test
    fun `a republish carries every pinnedAt through unchanged`() {
        val pins = listOf(pin("m1", 1_000L), pin("m2", 2_000L), pin("m3", 3_000L))

        val roundTripped = ChannelManager.pinsFromJson(ChannelManager.pinsToJson(pins))

        assertEquals(listOf(1_000L, 2_000L, 3_000L), roundTripped.map { it.pinnedAt })
    }

    @Test
    fun `the whole pin survives the round trip`() {
        val original = pin("m1", 4_242L)

        val back = ChannelManager.pinsFromJson(ChannelManager.pinsToJson(listOf(original))).single()

        assertEquals(original, back)
    }

    @Test
    fun `a pinnedAt written by the web is kept`() {
        val fromWeb = JSONArray().put(
            JSONObject()
                .put("targetId", "m1")
                .put("pinnedAt", 1_700_000_000_000L)
                .put("snapshot", JSONObject().put("sender", "0xabc").put("text", "hi"))
        )

        val parsed = ChannelManager.pinsFromJson(fromWeb).single()

        assertEquals(1_700_000_000_000L, parsed.pinnedAt)
        assertEquals("m1", parsed.targetId)
    }

    @Test
    fun `a pin with no pinnedAt parses as zero rather than now`() {
        val legacy = JSONArray().put(
            JSONObject().put("targetId", "m1")
                .put("snapshot", JSONObject().put("sender", "0xabc").put("text", "hi"))
        )

        assertEquals(0L, ChannelManager.pinsFromJson(legacy).single().pinnedAt)
    }

    @Test
    fun `JSON null names do not come back as the string null`() {
        val pins = listOf(
            ChannelManager.Pin("m1", "hi", "0xabc", senderName = null, ensName = null, pinnedAt = 1L)
        )

        val back = ChannelManager.pinsFromJson(ChannelManager.pinsToJson(pins)).single()

        assertNull(back.senderName)
        assertNull(back.ensName)
    }
}
