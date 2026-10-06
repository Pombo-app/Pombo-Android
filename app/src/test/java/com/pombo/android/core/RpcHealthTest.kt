package com.pombo.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcHealthTest {

    private val a = "https://a.example"
    private val b = "https://b.example"
    private val c = "https://c.example"

    @Test
    fun `reads the bridge report`() {
        val report = RpcHealth.parse(
            """{"applied":["$a","$b"],"usable":["$a"],"fallback":false,"verdicts":{
               "$a":{"ok":true,"block":4096,"at":10},
               "$b":{"ok":false,"reason":"overloaded","status":529,"at":11}}}"""
        )!!
        assertEquals(listOf(a, b), report.applied)
        assertEquals(listOf(a), report.usable)
        assertTrue(report.verdicts.getValue(a).ok)
        assertEquals(RpcHealth.Verdict(false, "overloaded", status = 529, at = 11), report.verdicts.getValue(b))
        assertFalse(report.fallback)
    }

    @Test
    fun `a malformed report is no report`() {
        assertNull(RpcHealth.parse("not json"))
    }

    @Test
    fun `an older report without the usable list reads as the applied one`() {
        assertEquals(listOf(a, c), RpcHealth.parse("""{"applied":["$a","$c"],"verdicts":{}}""")!!.usable)
    }

    @Test
    fun `rebuilds only for a changed set, after the minimum gap, and never mid-write`() {
        val gap = RpcHealth.MIN_REBUILD_INTERVAL_MS
        assertTrue(RpcHealth.shouldRebuild(listOf(a, b), listOf(a), 0, gap, writing = false))
        assertFalse(RpcHealth.shouldRebuild(listOf(a, b), listOf(b, a), 0, gap, writing = false))
        assertFalse(RpcHealth.shouldRebuild(listOf(a, b), listOf(a), 0, gap - 1, writing = false))
        assertFalse(RpcHealth.shouldRebuild(listOf(a, b), listOf(a), 0, gap, writing = true))
    }

    @Test
    fun `names the reason the way the web does`() {
        val say = { reason: String, status: Int?, code: Int?, behind: Int? ->
            RpcHealth.describe(RpcHealth.Verdict(false, reason, status, code, behind))
        }
        assertEquals("overloaded (HTTP 529)", say("overloaded", 529, null, null))
        assertEquals("rate limited", say("limited", null, null, null))
        assertEquals("HTTP 403", say("http", 403, null, null))
        assertEquals("refused the call (-32603)", say("refused", null, -32603, null))
        assertEquals("no answer in 3 s", say("timeout", null, null, null))
        assertEquals("unreachable (network or CORS)", say("unreachable", null, null, null))
        assertEquals("42 blocks behind", say("lagging", null, null, 42))
        assertEquals("wrong answer to a contract read", say("badresult", null, null, null))
    }
}
