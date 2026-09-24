package com.pombo.android.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Rebuilds the bridge when its Streamr node failed to start. The SDK starts
 * the node once per client and keeps a failed start for the client's whole
 * life: every publish and subscribe then fails at once, and only a new client
 * starts again.
 *
 * One rebuild at a time, and only on a validated network: the first at once,
 * the next ones on a backoff. A network coming back, or the app returning to
 * the foreground, skips what is left of the wait.
 */
class NodeRevival(
    private val scope: CoroutineScope,
    private val host: Host,
    private val delaysMs: LongArray = DELAYS_MS,
    private val reportTimeoutMs: Long = REPORT_TIMEOUT_MS
) {

    interface Host {
        fun networkUp(): Boolean
        /** Build a new client; its node's start comes back as [onAlive] or [onDead]. */
        fun rebuild()
        suspend fun sleep(ms: Long)
    }

    private var dead = false
    private var failedRebuilds = 0
    private var wait: Job? = null
    private var watchdog: Job? = null

    /** A rebuild whose node has not reported yet. */
    @Volatile var rebuilding = false
        private set

    @Synchronized fun onDead() {
        dead = true
        if (rebuilding) {
            rebuilding = false
            failedRebuilds++
            watchdog?.cancel()
        }
        wait?.cancel()
        val delayMs = if (failedRebuilds == 0) 0L
            else delaysMs[minOf(failedRebuilds - 1, delaysMs.lastIndex)]
        wait = scope.launch {
            if (delayMs > 0) host.sleep(delayMs)
            synchronized(this@NodeRevival) {
                wait = null
                attempt()
            }
        }
    }

    @Synchronized fun onAlive() {
        dead = false
        rebuilding = false
        failedRebuilds = 0
        wait?.cancel(); wait = null
        watchdog?.cancel(); watchdog = null
    }

    /** The network came back or the app is in front again: no reason to wait longer. */
    @Synchronized fun kick() {
        if (!dead || rebuilding || !host.networkUp()) return
        wait?.cancel(); wait = null
        attempt()
    }

    private fun attempt() {
        if (!dead || rebuilding || !host.networkUp()) return
        rebuilding = true
        watchdog?.cancel()
        // A start that never reports would block every later rebuild.
        watchdog = scope.launch {
            host.sleep(reportTimeoutMs)
            if (rebuilding) onDead()
        }
        host.rebuild()
    }

    companion object {
        val DELAYS_MS = longArrayOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L)
        const val REPORT_TIMEOUT_MS = 180_000L
    }
}
