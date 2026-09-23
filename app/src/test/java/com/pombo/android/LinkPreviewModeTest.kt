package com.pombo.android

import com.pombo.android.core.GraphApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A link carries only a stream id, so everything the preview knows comes from
 * the chain's metadata. Without the author visibility, a Sealed channel opens
 * as Visible, and what it publishes or answers from there follows the wrong mode.
 */
class LinkPreviewModeTest {

    private val h = ChannelManagerHarness()
    private val manager = h.manager

    @After fun tearDown() = h.stop()

    private fun info(wireIdentity: String?) = GraphApi.ChannelInfo(
        streamId = "0xowner/abc-1",
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
        createdBy = "0xowner",
        gateAddress = "0x7a3ee479b790578fb9ce885aa3356f79c4df0305",
        wireIdentity = wireIdentity
    )

    @Test fun `a Sealed channel previews as Sealed`() {
        manager.previewChannel(ExploreChannel.of(info("sealed")))

        val open = manager.current.value!!
        assertEquals("sealed", open.wireIdentity)
        assertEquals("0x7a3ee479b790578fb9ce885aa3356f79c4df0305", open.gateAddress)
        assertEquals(true, open.readOnly)
    }

    @Test fun `a Visible channel previews as Visible`() {
        manager.previewChannel(ExploreChannel.of(info("visible")))

        assertEquals("visible", manager.current.value!!.wireIdentity)
    }
}
