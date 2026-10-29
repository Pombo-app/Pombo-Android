package com.pombo.android.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionMonitorTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var now = 0L
    /** Parked sleeps and probes, released as virtual time passes. */
    private val timers = mutableListOf<Pair<Long, CompletableDeferred<Unit>>>()
    private var probes = 0
    private var reach: Boolean? = false
    private var probeTakesMs = 0L

    private suspend fun wait(ms: Long) {
        val gate = CompletableDeferred<Unit>()
        timers += (now + ms) to gate
        try { gate.await() } finally { gate.cancel() }
    }

    private val monitor = ConnectionMonitor(
        scope,
        probe = {
            probes++
            if (probeTakesMs > 0) wait(probeTakesMs)
            reach
        },
        sleep = { wait(it) },
        clock = { now }
    )

    /** Let [ms] of virtual time pass, firing every timer due on the way. */
    private fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val next = timers.filter { !it.second.isCompleted }.minByOrNull { it.first } ?: break
            if (next.first > end) break
            now = next.first
            next.second.complete(Unit)
        }
        now = end
    }

    @After fun tearDown() = scope.cancel()

    @Test fun `never probes while nothing is suspect`() {
        advance(300_000)
        assertEquals(0, probes)
        assertFalse(monitor.offline.value)
    }

    @Test fun `offline only after 60 s of failed probes, every 15 s`() {
        monitor.setNetworkUp(false)
        assertEquals(1, probes)
        advance(45_000)
        assertEquals(4, probes)
        assertFalse(monitor.offline.value)
        advance(15_000)
        assertEquals(5, probes)
        assertTrue(monitor.offline.value)
    }

    @Test fun `a slow probe does not stretch the cadence`() {
        probeTakesMs = 8_000
        monitor.setNetworkUp(false)
        advance(60_000)
        assertEquals(5, probes)
        assertFalse(monitor.offline.value)
        advance(8_000)
        assertTrue(monitor.offline.value)
    }

    @Test fun `a dead node with a reachable storage node is not offline`() {
        reach = true
        monitor.setNodeAlive(false)
        advance(300_000)
        assertFalse(monitor.offline.value)
        assertTrue(probes > 1)
    }

    @Test fun `a dead node with nothing to probe is not offline`() {
        reach = null
        monitor.setNodeAlive(false)
        advance(300_000)
        assertFalse(monitor.offline.value)
    }

    @Test fun `a short drop never shows the banner`() {
        monitor.setNetworkUp(false)
        advance(30_000)
        reach = true
        monitor.setNetworkUp(true)
        advance(300_000)
        assertFalse(monitor.offline.value)
    }

    @Test fun `back up exits once a probe gets through, after the settle wait`() {
        monitor.setNetworkUp(false)
        advance(60_000)
        assertTrue(monitor.offline.value)
        val before = probes
        reach = true
        monitor.setNetworkUp(true)
        advance(1_999)
        assertEquals(before, probes)
        assertTrue(monitor.offline.value)
        advance(1)
        assertFalse(monitor.offline.value)
        val after = probes
        advance(300_000)
        assertEquals(after, probes)
    }

    @Test fun `the node coming up clears offline`() {
        monitor.setNodeAlive(false)
        advance(60_000)
        assertTrue(monitor.offline.value)
        monitor.setNodeAlive(true)
        assertFalse(monitor.offline.value)
    }

    @Test fun `a probe that gets through restarts the 60 s window`() {
        monitor.setNodeAlive(false)
        advance(44_000)
        reach = true
        advance(1_000)
        reach = false
        advance(59_000)
        assertFalse(monitor.offline.value)
        advance(1_000)
        assertTrue(monitor.offline.value)
    }
}
