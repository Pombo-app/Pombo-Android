package com.pombo.android

import com.pombo.android.ChannelManager.SubscriptionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A ban seeded from disk on a channel whose newer -3 states the viewer cannot
 * open told an expired subscriber "a moderator removed you" on the first open
 * after launch, and "subscription expired" on the next.
 */
class ClientBanStandsTest {

    @Test
    fun `a lapsed subscriber is not told of a ban the -3 could not confirm`() {
        assertFalse(ChannelManager.clientBanStands(true, adminUnreadable = true, SubscriptionState.EXPIRED, paidChecked = true))
        assertFalse(ChannelManager.clientBanStands(true, adminUnreadable = null, SubscriptionState.UNSUBSCRIBED, paidChecked = true))
    }

    @Test
    fun `nothing is said about a ban while the paid standing is still being read`() {
        assertFalse(ChannelManager.clientBanStands(true, adminUnreadable = null, null, paidChecked = false))
        assertFalse(ChannelManager.clientBanStands(true, adminUnreadable = true, null, paidChecked = false))
    }

    @Test
    fun `a ban the -3 confirmed stands, lapse or not`() {
        assertTrue(ChannelManager.clientBanStands(true, adminUnreadable = false, SubscriptionState.EXPIRED, paidChecked = true))
        assertTrue(ChannelManager.clientBanStands(true, adminUnreadable = false, null, paidChecked = false))
    }

    @Test
    fun `a member with access who was cut out of the newer keys is still told of the ban`() {
        assertTrue(ChannelManager.clientBanStands(true, adminUnreadable = true, SubscriptionState.ACTIVE, paidChecked = true))
        assertTrue(ChannelManager.clientBanStands(true, adminUnreadable = true, null, paidChecked = true))
    }

    @Test
    fun `no listing, no ban`() {
        assertFalse(ChannelManager.clientBanStands(false, adminUnreadable = false, SubscriptionState.ACTIVE, paidChecked = true))
    }
}
