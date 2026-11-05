package com.pombo.android

import com.pombo.android.core.StreamConstants
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A publish to a -5 that does not exist succeeds all the same, so the
 * reaction would be seen live and gone on the next open. Where a reaction
 * goes is decided by whether storage keeps the channel's -5.
 */
class ReactionTargetTest {

    private val owner = "0x" + "ab".repeat(20)
    private val room = ChannelManagerHarness.channel("$owner/room-1")
    private val h = ChannelManagerHarness(channels = listOf(room))
    private val manager = h.manager
    private val interactions = StreamConstants.deriveInteractionsId(room.messageStreamId)
    private var storage: JSONObject = JSONObject()

    @Before fun setUp() {
        coEvery { h.bridge.call("getStreamStorageInfo", any(), any()) } answers { storage }
        manager.openChannel(room.messageStreamId)
        runBlocking { withTimeout(5_000) { while (manager.current.value?.messageStreamId != room.messageStreamId) delay(20) } }
    }

    @After fun tearDown() = h.stop()

    private fun react(): List<String> {
        h.published.clear()
        runBlocking { manager.sendReaction("m-1", "🔥", true) }
        return h.published.map { JSONObject(it) }
            .filter { it.optJSONObject("content")?.optString("type") == "reaction" }
            .map { "${it.getString("streamId")}#${it.getInt("partition")}" }
    }

    private val onFive = "$interactions#${StreamConstants.P_REACTIONS}"
    private val onOne = "${room.messageStreamId}#${StreamConstants.P_MESSAGES}"

    @Test
    fun `a -5 storage keeps gets the reaction`() {
        storage = JSONObject().put("ok", true).put("nodes", JSONArray().put("0xnode"))
        assertEquals(listOf(onFive), react())
    }

    @Test
    fun `a -5 with no storage node sends the reaction to the -1`() {
        storage = JSONObject().put("ok", true).put("nodes", JSONArray())
        assertEquals(listOf(onOne), react())
    }

    @Test
    fun `a -5 that does not exist sends the reaction to the -1`() {
        storage = JSONObject().put("ok", false).put("notFound", true).put("nodes", JSONArray())
        assertEquals(listOf(onOne), react())
    }

    @Test
    fun `a storage lookup that did not answer keeps the reaction on the -5`() {
        storage = JSONObject().put("ok", false).put("notFound", false).put("nodes", JSONArray())
        assertEquals(listOf(onFive), react())
    }

    @Test
    fun `storage is asked once per session`() {
        storage = JSONObject().put("ok", true).put("nodes", JSONArray())
        react()
        react()
        coVerify(exactly = 1) { h.bridge.call("getStreamStorageInfo", match { it.optString("streamId") == interactions }, any()) }
    }
}
