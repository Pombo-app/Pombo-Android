package com.pombo.android.bridge

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

class NodeRevivalTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var network = true
    private var rebuilds = 0
    /** Every sleep asked for, parked until the test lets that much time pass. */
    private val sleeps = mutableListOf<Pair<Long, CompletableDeferred<Unit>>>()

    private val revival = NodeRevival(scope, object : NodeRevival.Host {
        override fun networkUp() = network
        override fun rebuild() { rebuilds++ }
        override suspend fun sleep(ms: Long) {
            val gate = CompletableDeferred<Unit>()
            sleeps += ms to gate
            // A cancelled wait is over too.
            try { gate.await() } finally { gate.cancel() }
        }
    })

    private fun pendingWaits() = sleeps
        .filter { !it.second.isCompleted && !isWatchdog(it.first) }
        .map { it.first }

    private fun isWatchdog(ms: Long) = ms == NodeRevival.REPORT_TIMEOUT_MS

    private fun elapse(ms: Long) = sleeps
        .filter { it.first == ms && !it.second.isCompleted }
        .forEach { it.second.complete(Unit) }

    @After fun tearDown() = scope.cancel()

    @Test fun `a failed start is rebuilt at once on a validated network`() {
        revival.onDead()

        assertEquals(1, rebuilds)
        assertTrue(revival.rebuilding)
        assertTrue(pendingWaits().isEmpty())
    }

    @Test fun `each failed rebuild waits longer, up to the last step`() {
        revival.onDead()
        val waits = mutableListOf<Long>()
        repeat(6) {
            revival.onDead()
            val wait = pendingWaits().single()
            waits += wait
            elapse(wait)
        }

        assertEquals(listOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 300_000L), waits)
        assertEquals(7, rebuilds)
    }

    @Test fun `nothing is rebuilt without a network, and its return rebuilds at once`() {
        network = false
        revival.onDead()
        revival.kick()

        assertEquals(0, rebuilds)
        assertTrue(pendingWaits().isEmpty())

        network = true
        revival.kick()

        assertEquals(1, rebuilds)
    }

    @Test fun `a wait that ends without a network leaves the rebuild to the network's return`() {
        revival.onDead()
        revival.onDead()
        network = false
        elapse(15_000L)

        assertEquals(1, rebuilds)

        network = true
        revival.kick()

        assertEquals(2, rebuilds)
    }

    @Test fun `a kick skips what is left of the wait`() {
        revival.onDead()
        revival.onDead()
        assertEquals(listOf(15_000L), pendingWaits())

        revival.kick()

        assertEquals(2, rebuilds)
        assertTrue(pendingWaits().isEmpty())
    }

    @Test fun `never two rebuilds at once`() {
        revival.onDead()
        revival.kick()
        revival.kick()

        assertEquals(1, rebuilds)
    }

    @Test fun `a node that comes up resets the backoff and ignores kicks`() {
        revival.onDead()
        revival.onDead()
        elapse(15_000L)
        revival.onAlive()

        assertFalse(revival.rebuilding)
        revival.kick()
        assertEquals(2, rebuilds)

        revival.onDead()

        assertEquals(3, rebuilds)
        assertTrue(pendingWaits().isEmpty())
    }

    @Test fun `a rebuild that never reports counts as failed`() {
        revival.onDead()
        elapse(NodeRevival.REPORT_TIMEOUT_MS)

        assertFalse(revival.rebuilding)
        assertEquals(listOf(15_000L), pendingWaits())

        elapse(15_000L)

        assertEquals(2, rebuilds)
    }

    @Test fun `a report that arrives in time disarms the watchdog`() {
        revival.onDead()
        revival.onAlive()
        elapse(NodeRevival.REPORT_TIMEOUT_MS)

        assertEquals(1, rebuilds)
        assertTrue(pendingWaits().isEmpty())
    }
}
