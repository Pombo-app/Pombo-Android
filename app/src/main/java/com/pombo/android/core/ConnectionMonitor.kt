package com.pombo.android.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Decides when the app is offline, for the offline banner (twin of the web
 * connectionMonitor).
 *
 * A network that is lost or not validated, or a Streamr node that failed to
 * start, only makes the connection suspect. While suspect, and only then, a
 * storage node is probed every [probeIntervalMs]; OFFLINE is declared once the
 * suspicion has lasted [offlineAfterMs] with no probe getting through. A probe
 * that gets through, or the node coming up, clears it. Never probes the RPC:
 * offline must not cost chain reads.
 *
 * @param probe true reached, false failed, null nothing known to probe
 */
class ConnectionMonitor(
    private val scope: CoroutineScope,
    private val probe: suspend () -> Boolean?,
    private val sleep: suspend (Long) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val probeIntervalMs: Long = PROBE_INTERVAL_MS,
    private val offlineAfterMs: Long = OFFLINE_AFTER_MS,
    private val settleMs: Long = ONLINE_SETTLE_MS
) {
    private val _offline = MutableStateFlow(false)
    val offline: StateFlow<Boolean> = _offline.asStateFlow()

    private var networkUp = true
    private var nodeAlive = true
    private var suspectSince: Long? = null
    private var loop: Job? = null

    private val suspect get() = !networkUp || !nodeAlive

    @Synchronized fun setNetworkUp(up: Boolean) {
        val cameUp = up && !networkUp
        networkUp = up
        // Requests in flight when the network comes back tend to fail.
        if (cameUp && (suspect || _offline.value)) start(settleMs) else update()
    }

    @Synchronized fun setNodeAlive(alive: Boolean) {
        nodeAlive = alive
        if (alive) reached()
        update()
    }

    private fun update() {
        if (suspect || _offline.value) {
            if (suspectSince == null) suspectSince = clock()
            if (loop?.isActive != true) start(0)
        } else {
            loop?.cancel()
            loop = null
            suspectSince = null
        }
    }

    private fun start(firstWaitMs: Long) {
        loop?.cancel()
        if (suspectSince == null) suspectSince = clock()
        loop = scope.launch {
            if (firstWaitMs > 0) sleep(firstWaitMs)
            while (true) {
                val startedAt = clock()
                val result = try {
                    probe()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    false
                }
                if (!onResult(result)) break
                sleep((probeIntervalMs - (clock() - startedAt)).coerceAtLeast(0))
            }
        }
    }

    /** False when there is nothing left to watch. */
    @Synchronized private fun onResult(result: Boolean?): Boolean {
        if (suspect || _offline.value) {
            if (result == true) {
                reached()
            } else if ((result == false || !networkUp)
                && clock() - (suspectSince ?: clock()) >= offlineAfterMs) {
                _offline.value = true
            }
        }
        if (suspect || _offline.value) return true
        suspectSince = null
        return false
    }

    private fun reached() {
        if (!networkUp) return
        suspectSince = if (suspect) clock() else null
        _offline.value = false
    }

    companion object {
        const val PROBE_INTERVAL_MS = 15_000L
        const val OFFLINE_AFTER_MS = 60_000L
        const val ONLINE_SETTLE_MS = 2_000L
    }
}
