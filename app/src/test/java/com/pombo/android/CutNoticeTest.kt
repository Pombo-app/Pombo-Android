package com.pombo.android

import com.pombo.android.ui.ToastKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What removing a member says about the channel key. Only the owner rotates
 * it: their own device does it now or owes it, and a moderator's removal is
 * left to the owner's next open.
 */
class CutNoticeTest {

    @Test fun `tells the owner it is done when the key rotated`() {
        assertEquals(CutNotice("Member removed", ToastKind.SUCCESS, 3000L),
            cutNotice("Member removed", rotated = true, owner = true))
    }

    @Test fun `tells the owner the rotation is still owed`() {
        assertEquals(CutNotice("Member removed. The channel key rotates the next time the app connects.",
            ToastKind.WARNING, 5000L),
            cutNotice("Member removed", rotated = false, owner = true))
    }

    @Test fun `tells a moderator the owner rotates the key`() {
        assertEquals(CutNotice("Member removed. The key rotates when the owner next opens the channel.",
            ToastKind.INFO, 5000L),
            cutNotice("Member removed", rotated = false, owner = false))
    }
}
