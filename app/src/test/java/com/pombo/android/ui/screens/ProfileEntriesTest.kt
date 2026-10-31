package com.pombo.android.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileEntriesTest {

    @Test
    fun `a guest only gets Add account, being the guest session already`() {
        assertEquals(listOf("Add account"), profileEntries(isGuest = true).map { it.label })
    }

    @Test
    fun `an account keeps Add account and Browse as guest`() {
        assertEquals(
            listOf("Add account", "Browse as guest"),
            profileEntries(isGuest = false).map { it.label }
        )
    }
}
