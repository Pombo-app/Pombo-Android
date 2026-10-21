package com.pombo.android.core.channels

import android.util.Log
import com.pombo.android.data.PendingCreate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Channel creations started on this device and not finished (twin of the web
 * PendingCreates). The record is written before the first transaction, so a
 * kill part way through leaves a creation to finish or discard instead of a
 * paid channel nobody holds; the chain says which.
 *
 * @param streamExists throws when the chain cannot be read
 * @param findGate the clone the creation deployed, null when none; throws when the chain cannot be read
 */
class PendingCreates(
    private val records: () -> List<PendingCreate>,
    private val put: (PendingCreate) -> Unit,
    private val remove: (String) -> Unit,
    private val streamExists: suspend (String) -> Boolean,
    private val findGate: suspend (PendingCreate) -> String?,
    private val now: () -> Long = System::currentTimeMillis
) {
    enum class State { RUNNING, RESUMABLE, WAITING, UNREADABLE, DISCARDED }

    private val running = ConcurrentHashMap.newKeySet<String>()
    private val states = ConcurrentHashMap<String, State>()
    private val _resumable = MutableStateFlow<List<PendingCreate>>(emptyList())

    /** The records the chain shows started, for Finish / Discard. */
    val resumable: StateFlow<List<PendingCreate>> = _resumable.asStateFlow()

    fun get(basePath: String): PendingCreate? = records().find { it.basePath == basePath }

    fun begin(record: PendingCreate) {
        running += record.basePath
        put(record)
    }

    /** False when this session is already creating it. */
    fun claim(basePath: String): Boolean = running.add(basePath)

    fun update(record: PendingCreate) = put(record)

    fun complete(basePath: String) {
        remove(basePath)
        states.remove(basePath)
        running -= basePath
        publish()
    }

    fun release(basePath: String) {
        running -= basePath
    }

    fun discard(basePath: String) {
        check(basePath !in running) { "This channel is still being created" }
        remove(basePath)
        states.remove(basePath)
        publish()
    }

    /** Re-reads the records, after the account they belong to changed. */
    fun refresh() = publish()

    /**
     * What the chain holds of one record. Anything of it there (the gate
     * clone, or the -1 for channels without one) makes it resumable; nothing
     * after [MAX_AGE_MS] drops the record.
     */
    suspend fun reconcile(record: PendingCreate): State {
        if (record.basePath in running) return State.RUNNING
        val landed = try {
            if (record.type == "gated") {
                var gateAddress = record.gateAddress
                if (gateAddress == null) {
                    gateAddress = findGate(record)
                    if (gateAddress != null) {
                        if (record.basePath in running || get(record.basePath) == null) return State.RUNNING
                        put(record.copy(gateAddress = gateAddress))
                    }
                }
                gateAddress != null
            } else {
                streamExists("${record.basePath}-1")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Interrupted channel creation unsettled, chain unreadable: ${e.message}")
            return set(record, State.UNREADABLE)
        }
        if (landed) return set(record, State.RESUMABLE)
        if (now() - record.startedAt < MAX_AGE_MS) return set(record, State.WAITING)
        if (record.basePath in running) return State.RUNNING
        remove(record.basePath)
        states.remove(record.basePath)
        publish()
        Log.i(TAG, "Interrupted channel creation never reached the chain, dropped: ${record.basePath}")
        return State.DISCARDED
    }

    suspend fun reconcileAll(): Map<String, State> = records().associate { it.basePath to reconcile(it) }

    private fun set(record: PendingCreate, state: State): State {
        states[record.basePath] = state
        publish()
        return state
    }

    private fun publish() {
        _resumable.value = records().filter { states[it.basePath] == State.RESUMABLE }
    }

    companion object {
        private const val TAG = "PendingCreates"

        /** Nothing on chain after this long: whatever was sent is not landing. */
        const val MAX_AGE_MS = 60 * 60 * 1000L
    }
}
