package com.pombo.android.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class ExploreTypeLabelTest {

    @Test
    fun `a gated channel is labelled by its gate mode`() {
        assertEquals("Open", exploreTypeLabel("public", null))
        assertEquals("Password", exploreTypeLabel("password", null))
        assertEquals("Paid", exploreTypeLabel("gated", GateModes.PAID))
        assertEquals("Closed", exploreTypeLabel("gated", GateModes.NONE))
        assertEquals("Gated", exploreTypeLabel("gated", GateModes.TOKEN))
        assertEquals("Gated", exploreTypeLabel("gated", null))
        assertEquals("Open · Read-Only", exploreTypeLabel("public", null, readOnly = true))
        assertEquals("Paid · Read-Only", exploreTypeLabel("gated", GateModes.PAID, readOnly = true))
    }
}
