package com.pombo.android.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Who may reach for each level of a ban. The gate's `ban()` is onlyOwner, so
 * offering it to a moderator is a transaction that reverts; a moderator hides
 * by delta instead. The message menu and the members list ask the same thing.
 */
class BanRightsTest {

    @Test fun `the owner of a gated channel has both levels`() {
        assertEquals(BanRights(client = true, protocol = true),
            banRights(owner = true, moderatesGate = false, gated = true))
    }

    @Test fun `a moderator hides but is never offered the gate`() {
        assertEquals(BanRights(client = true, protocol = false),
            banRights(owner = false, moderatesGate = true, gated = true))
    }

    @Test fun `the owner of a channel without a gate only hides`() {
        assertEquals(BanRights(client = true, protocol = false),
            banRights(owner = true, moderatesGate = false, gated = false))
    }

    @Test fun `anyone else has neither`() {
        assertEquals(BanRights(client = false, protocol = false),
            banRights(owner = false, moderatesGate = false, gated = true))
    }
}
