package com.pombo.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a DM conversation is allowed to show: my messages and this peer's,
 * nothing else.
 *
 * A message from a third account was found sitting in one conversation's sent
 * slice, carried to every device by the state sync. Fixing the routing only
 * stops new ones — the display has to refuse the ones already stored, which is
 * what [ChannelManager.dmTimelineKeeps] does for both halves of the timeline.
 */
class DmTimelineTest {

    private val me = "0xme11111111111111111111111111111111111111"
    private val peer = "0xpeer2222222222222222222222222222222222"
    private val stranger = "0xstranger33333333333333333333333333333333"

    @Test
    fun `my own message stays in the sent half`() {
        assertTrue(ChannelManager.dmTimelineKeeps(me, me))
        assertTrue(ChannelManager.dmTimelineKeeps(me.uppercase(), me))
    }

    @Test
    fun `a sent record naming another account is refused`() {
        assertFalse(ChannelManager.dmTimelineKeeps(stranger, me))
        assertFalse(ChannelManager.dmTimelineKeeps(peer, me))
    }

    @Test
    fun `this peer's message stays in the received half`() {
        assertTrue(ChannelManager.dmTimelineKeeps(peer, peer))
    }

    @Test
    fun `a received message from a third account is refused`() {
        assertFalse(ChannelManager.dmTimelineKeeps(stranger, peer))
    }

    /** The oldest entries predate the account field; silence is not guilt. */
    @Test
    fun `a record naming nobody is kept on both sides`() {
        assertTrue(ChannelManager.dmTimelineKeeps("", me))
        assertTrue(ChannelManager.dmTimelineKeeps("", peer))
    }
}
