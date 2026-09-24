package com.pombo.android

import com.pombo.android.core.StreamConstants
import com.pombo.android.data.Channel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner's access sweep reads the gate, which can take a minute, and then
 * saves what it learned. Whatever happened to the channel record during that
 * read (a Join, a sync import) has to survive the save.
 */
class AccessSweepRecordTest {

    private val h = ChannelManagerHarness()
    private val manager = h.manager
    private val streamId = "${h.me}/sealed-1"

    @After fun tearDown() = h.stop()

    private val joined = Channel(
        messageStreamId = streamId,
        ephemeralStreamId = StreamConstants.deriveEphemeralId(streamId),
        adminStreamId = StreamConstants.deriveAdminId(streamId),
        keysStreamId = StreamConstants.deriveKeysId(streamId),
        name = "Sealed",
        type = "gated",
        gateAddress = GATE,
        wireIdentity = "sealed",
        joinedAt = 1_000L,
        exposure = "hidden",
        createdBy = h.me
    )

    /** What a link preview of the same channel looks like before the Join. */
    private val preview = joined.copy(
        name = "sealed-1", wireIdentity = null, joinedAt = null, exposure = "visible"
    )

    private fun gateAnswers(withAccess: List<String> = emptyList(), onRead: () -> Unit = {}) {
        coEvery { h.bridge.call("gateInfo", any()) } returns JSONObject().put("mode", 1)
        coEvery { h.bridge.call("gateMembers", any(), any()) } answers {
            onRead()
            val members = JSONArray().put(JSONObject()
                .put("address", h.me).put("isOwner", true).put("access", true))
            withAccess.forEach { members.put(JSONObject().put("address", it).put("access", true)) }
            JSONObject().put("members", members)
        }
    }

    @Test fun `a sweep on a preview neither reads the gate nor writes`() = runBlocking {
        gateAnswers()
        manager._current.value = preview

        manager.rotateForLostAccess(preview)

        coVerify(exactly = 0) { h.bridge.call("gateMembers", any(), any()) }
        assertTrue(manager.channels.value.isEmpty())
    }

    @Test fun `the sweep saves onto the record as it is after the gate read`() = runBlocking {
        manager._channels.value = listOf(joined)
        manager._current.value = joined
        gateAnswers(onRead = {
            manager._channels.value = listOf(joined.copy(name = "Renamed elsewhere"))
        })

        manager.rotateForLostAccess(preview)

        val saved = manager.channels.value.single()
        assertEquals("Renamed elsewhere", saved.name)
        assertEquals("sealed", saved.wireIdentity)
        assertEquals(1_000L, saved.joinedAt)
        assertEquals("hidden", saved.exposure)
        assertEquals(listOf(h.me), saved.accessSnapshot)
    }

    @Test fun `a sweep saves and schedules a sync push only when the gate changed the record`() = runBlocking {
        var pushes = 0
        manager.onLocalStateChanged = { pushes++ }
        manager._channels.value = listOf(joined)
        manager._current.value = joined
        gateAnswers()

        manager.rotateForLostAccess(joined)
        manager.rotateForLostAccess(joined)
        assertEquals(1, pushes)

        gateAnswers(withAccess = listOf(MEMBER))
        manager.rotateForLostAccess(joined)
        manager.rotateForLostAccess(joined)
        assertEquals(2, pushes)
        verify(exactly = 2) { h.store.save(any()) }
    }

    private companion object {
        const val GATE = "0x7a3ee479b790578fb9ce885aa3356f79c4df0305"
        const val MEMBER = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8"
    }
}
