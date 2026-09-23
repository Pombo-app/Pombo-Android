package com.pombo.android

import com.pombo.android.core.GraphApi
import io.mockk.coEvery
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A preview is read-only, so its permissions are zeroed. Joining from it has
 * to ask again, or the owner who arrived by link sees no Moderation and no
 * Delete until the channel is reopened.
 */
class JoinFromPreviewPermsTest {

    private val h = ChannelManagerHarness()
    private val manager = h.manager
    private val gate = "0x7a3ee479b790578fb9ce885aa3356f79c4df0305"
    private val streamId = "${h.me}/abc-1"

    @After fun tearDown() = h.stop()

    private fun info() = GraphApi.ChannelInfo(
        streamId = streamId,
        name = null,
        displayName = "abc-1",
        type = "gated",
        description = "",
        language = "",
        category = "",
        readOnly = true,
        exposure = "hidden",
        createdAt = 0L,
        updatedAt = 0L,
        createdBy = h.me,
        gateAddress = gate,
        wireIdentity = "sealed"
    )

    @Test
    fun `the owner who joins from a link preview gets the owner's surfaces back`() = runBlocking {
        coEvery { h.bridge.call(eq("gateCheckAccess"), any()) } returns JSONObject().put("access", true)
        coEvery { h.bridge.call(eq("getStreamInfo"), any()) } returns JSONObject().put(
            "metadata", JSONObject().put(
                "description",
                JSONObject().put("a", "pombo").put("t", "gated").put("n", "abc").put("g", gate).put("m", 1).toString()
            )
        )
        manager.previewChannel(ExploreChannel.of(info()))
        assertFalse(manager.perms.value.canDelete)

        manager.joinPreview()

        assertTrue(manager.perms.value.canDelete)
        assertTrue(manager.perms.value.canGrant)
    }
}
