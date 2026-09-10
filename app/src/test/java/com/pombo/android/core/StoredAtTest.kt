package com.pombo.android.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredAtTest {

    private val tolerance = 5 * 60_000L

    @Test
    fun `judgeTime prefers storedAt and falls back to the declared timestamp`() {
        assertEquals(5100L, StoredAt.judgeTime(JSONObject().put("timestamp", 5000L).put("storedAt", 5100L)))
        assertEquals(5000L, StoredAt.judgeTime(JSONObject().put("timestamp", 5000L)))
        assertEquals(5000L, StoredAt.judgeTime(JSONObject().put("timestamp", 5000L).put("storedAt", 0L)))
        assertEquals(0L, StoredAt.judgeTime(null))
    }

    @Test
    fun `forwardDated flags a declared timestamp past the tolerance after the receive time`() {
        assertFalse(StoredAt.forwardDated(JSONObject().put("timestamp", 5000L).put("storedAt", 5100L), tolerance))
        assertFalse(StoredAt.forwardDated(JSONObject().put("timestamp", 5100L + tolerance).put("storedAt", 5100L), tolerance))
        assertTrue(StoredAt.forwardDated(JSONObject().put("timestamp", 5100L + tolerance + 1).put("storedAt", 5100L), tolerance))
    }

    @Test
    fun `forwardDated cannot judge without a receive time`() {
        assertFalse(StoredAt.forwardDated(JSONObject().put("timestamp", Long.MAX_VALUE / 2), tolerance))
        assertFalse(StoredAt.forwardDated(null, tolerance))
    }
}
