package com.pombo.android

import io.mockk.coEvery
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the owner's own surfaces (moderation, storage, delete) are gated on.
 *
 * Both cases here were seen on a device: a flaky RPC answered nothing for a
 * channel under the user's own address, and the empty verdict was cached, so
 * the owner lost Moderation and Delete Channel until the app restarted.
 */
class OwnerPermissionTest {

    private val theirsId = "0xsomeoneelse/room-1"

    private val h = ChannelManagerHarness(
        channels = listOf(
            ChannelManagerHarness.channel(
                "${com.pombo.android.core.EthereumSigner.address(
                    "0x4f3edf983ac636a65a842ce7c78d9aa706d3b113bce9c46f30d7d21715b23b1d"
                ).lowercase()}/room-1"
            ),
            ChannelManagerHarness.channel(theirsId, name = "theirs")
        )
    )
    private val manager = h.manager
    private val mineId = "${h.me}/room-1"

    @After fun tearDown() = h.stop()

    @Test
    fun `a stream under my own address needs no permission read`() {
        coEvery { h.bridge.call(eq("checkPermissions"), any(), any()) } throws
            IllegalStateException("RPC down")
        manager.openChannel(mineId)
        assertTrue(manager.perms.value.canDelete)
        assertTrue(manager.perms.value.canGrant)
    }

    @Test
    fun `someone else's stream still goes through the read`() {
        coEvery { h.bridge.call(eq("checkPermissions"), any(), any()) } returns
            JSONObject().put("canDelete", false).put("canGrant", false)
        manager.openChannel(theirsId)
        assertFalse(manager.perms.value.canDelete)
    }

    /**
     * A read that did not answer must not be remembered as "holds nothing":
     * the next open has to ask again, or one bad RPC hides the surfaces for
     * the whole session.
     */
    @Test
    fun `a failed read is not cached as a verdict`() {
        coEvery { h.bridge.call(eq("checkPermissions"), any(), any()) } throws
            IllegalStateException("RPC down")
        manager.openChannel(theirsId)
        assertFalse(manager.perms.value.canDelete)

        coEvery { h.bridge.call(eq("checkPermissions"), any(), any()) } returns
            JSONObject().put("canDelete", true).put("canGrant", true)
        manager.openChannel(theirsId)
        assertTrue(manager.perms.value.canDelete)
    }
}
