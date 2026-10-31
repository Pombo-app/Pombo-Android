package com.pombo.android.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestHomeCardTest {

    @Test
    fun `a guest with an empty Home list gets the card`() {
        assertTrue(guestCardShown(isGuest = true, channelCount = 0))
    }

    @Test
    fun `goes once the guest has a channel`() {
        assertFalse(guestCardShown(isGuest = true, channelCount = 1))
    }

    @Test
    fun `never shows for an account`() {
        assertFalse(guestCardShown(isGuest = false, channelCount = 0))
    }
}
