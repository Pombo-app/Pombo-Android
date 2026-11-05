package com.pombo.android.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A DM inbox diagnosis is read the same way on both clients: a false is a
 * finding, a null is a check the chain did not answer, and only findings
 * are repaired and charged for.
 */
class DmInboxHealthTest {

    private fun healthy() = JSONObject()
        .put("messageStream", JSONObject().put("exists", true).put("partitions", 13)
            .put("partitionsCorrect", true).put("publicKeyPresent", true))
        .put("ephemeralStream", JSONObject().put("exists", true))
        .put("permissions", JSONObject().put("messagePublicPublish", true).put("ephemeralPublicPublish", true))
        .put("storage", JSONObject().put("enabled", true))

    @Test
    fun `a healthy inbox has nothing to report or repair`() {
        assertEquals(DmInboxHealth.Findings(emptyList(), emptyList()), DmInboxHealth.findings(healthy()))
        assertEquals(0L, GasEstimator.inboxRepairUnits(healthy()))
    }

    @Test
    fun `a false is an issue and a null is not checked`() {
        val d = healthy()
        d.getJSONObject("permissions").put("messagePublicPublish", false)
        d.getJSONObject("storage").put("enabled", JSONObject.NULL)
        assertEquals(
            DmInboxHealth.Findings(listOf("message permissions"), listOf("storage")),
            DmInboxHealth.findings(d)
        )
    }

    @Test
    fun `only what is broken is charged`() {
        val d = healthy()
        d.getJSONObject("permissions").put("messagePublicPublish", false)
        d.getJSONObject("storage").put("enabled", JSONObject.NULL)
        val one = GasEstimator.inboxRepairUnits(d)
        d.getJSONObject("storage").put("enabled", false)
        assertTrue(one > 0)
        assertTrue(GasEstimator.inboxRepairUnits(d) > one)
    }

    @Test
    fun `a missing stream is one issue, and what lives on it is not asked`() {
        val d = healthy()
        d.put("messageStream", JSONObject().put("exists", false)
            .put("partitionsCorrect", false).put("publicKeyPresent", false))
        d.getJSONObject("permissions").put("messagePublicPublish", false)
        d.getJSONObject("storage").put("enabled", false)
        assertEquals(
            DmInboxHealth.Findings(listOf("message stream", "message permissions", "storage"), emptyList()),
            DmInboxHealth.findings(d)
        )
    }

    @Test
    fun `a stream that could not be read is not called missing`() {
        val d = healthy()
        d.put("messageStream", JSONObject().put("exists", JSONObject.NULL))
        d.getJSONObject("permissions").put("messagePublicPublish", JSONObject.NULL)
        d.getJSONObject("storage").put("enabled", JSONObject.NULL)
        assertEquals(DmInboxHealth.Findings(emptyList(), listOf("message stream")), DmInboxHealth.findings(d))
        assertEquals(0L, GasEstimator.inboxRepairUnits(d))
    }
}
