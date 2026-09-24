package com.pombo.android.core.channels

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * The epoch rotation owed after a ban or a removal. Until it goes out, the
 * address cut on the gate still holds the current key and reads everything
 * published under it.
 *
 * It fails when the Streamr node failed to start, and the SDK keeps that
 * failure until the bridge is rebuilt: so the debt is kept on this device,
 * retried while the process lives, taken up again on every bridge connect,
 * and settled before the admin's own sends.
 */
class RotationRetry(
    private val scope: CoroutineScope,
    private val host: Host,
    private val delaysMs: LongArray = DELAYS_MS
) {

    interface Host {
        fun account(): String?
        /** Announce a new epoch; throws when the announce did not go out. */
        suspend fun rotate(messageStreamId: String)
        /** Record the addresses as rotated for, so the sweep leaves them alone. */
        suspend fun covered(messageStreamId: String, addresses: Set<String>)
        /** False once this account no longer owns a stored channel of that id. */
        fun stillOwned(messageStreamId: String): Boolean
        fun loadOwed(key: String): List<String>
        fun saveOwed(key: String, addresses: List<String>)
        suspend fun sleep(ms: Long)
    }

    private val locks = ConcurrentHashMap<String, Mutex>()
    private val loops = HashMap<String, Job>()

    private fun key(messageStreamId: String) =
        "rotation-owed|${host.account()?.lowercase()}|$messageStreamId"

    private fun owed(messageStreamId: String): Set<String> = host.loadOwed(key(messageStreamId)).toSet()

    private fun update(messageStreamId: String, change: (Set<String>) -> Set<String>) = synchronized(this) {
        host.saveOwed(key(messageStreamId), change(owed(messageStreamId)).toList())
    }

    fun isOwed(messageStreamId: String): Boolean = owed(messageStreamId).isNotEmpty()

    /**
     * Rotate for [addresses] now; on failure keep them owed and retry.
     * @return true when the rotation went out now
     */
    suspend fun rotateFor(messageStreamId: String, addresses: Collection<String>): Boolean {
        update(messageStreamId) { it + addresses.map { a -> a.lowercase() } }
        if (attempt(messageStreamId)) return true
        ensureLoop(messageStreamId)
        return false
    }

    /** Take up what an earlier session or bridge left owed on these channels. */
    fun resume(messageStreamIds: Collection<String>) {
        for (id in messageStreamIds) {
            if (!isOwed(id)) continue
            scope.launch { if (!attempt(id)) ensureLoop(id) }
        }
    }

    /** Before the admin publishes: an owed rotation goes first, or the publish does not go. */
    suspend fun settle(messageStreamId: String) {
        if (!isOwed(messageStreamId)) return
        if (!attempt(messageStreamId)) throw IllegalStateException(OWED_MESSAGE)
    }

    private suspend fun attempt(messageStreamId: String): Boolean =
        locks.computeIfAbsent(messageStreamId) { Mutex() }.withLock {
            val addresses = owed(messageStreamId)
            if (addresses.isEmpty()) return@withLock true
            try {
                host.rotate(messageStreamId)
            } catch (e: Exception) {
                Log.w(TAG, "Rotation owed to ${addresses.size} cut address(es) failed, retrying: ${e.message}")
                return@withLock false
            }
            update(messageStreamId) { it - addresses }
            Log.i(TAG, "Rotated the epoch owed to ${addresses.size} cut address(es)")
            try {
                host.covered(messageStreamId, addresses)
            } catch (e: Exception) {
                Log.w(TAG, "Recording the rotation failed: ${e.message}")
            }
            !isOwed(messageStreamId)
        }

    private fun ensureLoop(messageStreamId: String) = synchronized(loops) {
        if (loops[messageStreamId]?.isActive == true) return@synchronized
        loops[messageStreamId] = scope.launch {
            var round = 0
            while (isOwed(messageStreamId)) {
                host.sleep(delaysMs[minOf(round, delaysMs.lastIndex)])
                round++
                if (!host.stillOwned(messageStreamId)) {
                    update(messageStreamId) { emptySet() }
                    return@launch
                }
                attempt(messageStreamId)
            }
        }
    }

    companion object {
        private const val TAG = "PomboChannels"
        val DELAYS_MS = longArrayOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L)
        const val OWED_MESSAGE = "Waiting to rotate the channel key. It rotates the next time the app connects."
    }
}
