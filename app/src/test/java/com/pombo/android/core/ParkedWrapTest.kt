package com.pombo.android.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A wrap that arrives before the announce that legitimises it.
 *
 * The responder answers a key request as soon as it hears it, so on a cold
 * subscribe the wrap regularly overtakes the announce. Dropping it cost a
 * measured 35s stall: the key only landed when a later retry happened to
 * arrive after the announce.
 */
class ParkedWrapTest {

    private fun wrap(id: String) = JSONObject().put("keyId", id)

    private fun parked() = LinkedHashMap<Int, MutableList<JSONObject>>()

    @Test
    fun `a wrap waits for its announce and is handed over once`() {
        val p = parked()
        EpochKeyManager.parkWrap(p, 2, wrap("k2"))

        val first = EpochKeyManager.takeParkedWraps(p, 2)
        assertEquals(1, first.size)
        assertEquals("k2", first[0].getString("keyId"))
        assertTrue("a replayed wrap must not be replayed again",
            EpochKeyManager.takeParkedWraps(p, 2).isEmpty())
    }

    @Test
    fun `another epoch's announce does not release it`() {
        val p = parked()
        EpochKeyManager.parkWrap(p, 3, wrap("k3"))
        assertTrue(EpochKeyManager.takeParkedWraps(p, 2).isEmpty())
        assertEquals(1, EpochKeyManager.takeParkedWraps(p, 3).size)
    }

    /** Unverifiable material from strangers must not become a memory leak. */
    @Test
    fun `the wait list is bounded per epoch`() {
        val p = parked()
        repeat(50) { EpochKeyManager.parkWrap(p, 1, wrap("k$it")) }
        assertEquals(4, EpochKeyManager.takeParkedWraps(p, 1).size)
    }

    @Test
    fun `the wait list is bounded across epochs`() {
        val p = parked()
        for (epoch in 1..40) EpochKeyManager.parkWrap(p, epoch, wrap("k$epoch"))
        assertEquals(8, p.size)
        assertTrue("the oldest epochs go first", EpochKeyManager.takeParkedWraps(p, 1).isEmpty())
        assertEquals(1, EpochKeyManager.takeParkedWraps(p, 40).size)
    }

    @Test
    fun `an epoch of zero is not a wait list`() {
        val p = parked()
        EpochKeyManager.parkWrap(p, 0, wrap("k0"))
        assertTrue(p.isEmpty())
    }

    /**
     * The shared keys race the same way, and losing that race is worse: a
     * member who never adopts the interactions key cannot react at all, in a
     * read-only channel where reacting is the whole of participation.
     */
    @Test
    fun `a shared-key wrap waits for its announce`() {
        val p = LinkedHashMap<String, MutableList<JSONObject>>()
        EpochKeyManager.parkPubWrap(p, "int-1", wrap("int-1"))

        assertTrue("another key's announce does not release it",
            EpochKeyManager.takeParkedPubWraps(p, "pub-1").isEmpty())
        val released = EpochKeyManager.takeParkedPubWraps(p, "int-1")
        assertEquals(1, released.size)
        assertEquals("int-1", released[0].getString("keyId"))
        assertTrue(EpochKeyManager.takeParkedPubWraps(p, "int-1").isEmpty())
    }

    @Test
    fun `a shared-key wrap without a keyId is not a wait list`() {
        val p = LinkedHashMap<String, MutableList<JSONObject>>()
        EpochKeyManager.parkPubWrap(p, "", wrap(""))
        assertTrue(p.isEmpty())
    }

    @Test
    fun `the shared-key wait list is bounded`() {
        val p = LinkedHashMap<String, MutableList<JSONObject>>()
        repeat(50) { EpochKeyManager.parkPubWrap(p, "int-1", wrap("int-1")) }
        assertEquals(4, EpochKeyManager.takeParkedPubWraps(p, "int-1").size)
        for (i in 1..40) EpochKeyManager.parkPubWrap(p, "k$i", wrap("k$i"))
        assertEquals(8, p.size)
    }
}
