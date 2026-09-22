package com.pombo.android.core.channels

import android.util.Log
import com.pombo.android.ChannelManager
import com.pombo.android.data.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reads a published message back from storage until it is there (web
 * DeliveryConfirm.js). DMs are never read back (the sender cannot read the
 * peer's inbox), and a message whose reads all failed stays sent: an
 * unreachable node proves nothing.
 */
internal class DeliveryConfirm(private val manager: ChannelManager) {

    companion object {
        private const val TAG = "PomboDelivery"
        internal val CONFIRM_DELAYS_MS = longArrayOf(5_000L, 10_000L, 20_000L, 40_000L)
        internal const val UNDELIVERED_REASON = "Not delivered: the storage node did not record this message"
    }

    private class Entry(val id: String, val envelopeTs: Long, val since: Long) {
        var reads = 0
        var verified = false
    }

    private val inFlight = HashMap<String, LinkedHashMap<String, Entry>>()
    private val jobs = HashMap<String, Job>()
    internal var sleep: suspend (Long) -> Unit = { delay(it) }
    internal var now: () -> Long = { System.currentTimeMillis() }

    /** A publish just went out: remember it and see it to storage. */
    fun track(channel: Channel, id: String, envelopeTs: Long) {
        if (channel.type == "dm" || id.isEmpty() || envelopeTs <= 0L) return
        val streamId = channel.messageStreamId
        synchronized(this) {
            inFlight.getOrPut(streamId) { LinkedHashMap() }[id] = Entry(id, envelopeTs, now())
            if (jobs[streamId]?.isActive != true) {
                jobs[streamId] = manager.scope.launch { loop(streamId) }
            }
        }
    }

    /** Messages of a channel still waiting for storage. */
    fun pending(streamId: String): Int = synchronized(this) { inFlight[streamId]?.size ?: 0 }

    private fun snapshot(streamId: String): List<Entry> =
        synchronized(this) { inFlight[streamId]?.values?.toList() ?: emptyList() }

    private fun remove(streamId: String, id: String): Boolean =
        synchronized(this) { inFlight[streamId]?.remove(id) != null }

    private fun clear(streamId: String) {
        synchronized(this) { inFlight.remove(streamId) }
    }

    private fun due(entry: Entry): Long = entry.since + CONFIRM_DELAYS_MS.take(entry.reads + 1).sum()

    private suspend fun loop(streamId: String) {
        val label = "delivery on ${streamId.takeLast(20)}"
        val providers = runCatching { manager.storageEndpoints.resolve(streamId) }.getOrNull()
        if (providers != null && providers.isEmpty()) {
            clear(streamId)
            Log.d(TAG, "$label: the channel has no storage, nothing to confirm")
            return
        }
        while (true) {
            val all = snapshot(streamId)
            if (all.isEmpty()) break
            val wait = all.minOf { due(it) } - now()
            if (wait > 0) sleep(wait)
            val current = snapshot(streamId)
            val dueNow = current.filter { due(it) <= now() }
            if (dueNow.isEmpty()) continue

            val rows = runCatching {
                manager.readEnvelopeTimes(
                    streamId, current.minOf { it.envelopeTs } - 1, current.maxOf { it.envelopeTs } + 1)
            }.getOrNull()
            dueNow.forEach { it.reads += 1 }
            if (rows != null) {
                for (entry in current) {
                    entry.verified = true
                    if (entry.envelopeTs !in rows) continue
                    if (remove(streamId, entry.id)) {
                        manager.markDelivered(entry.id)
                        Log.d(TAG, "$label: ${entry.id} confirmed on storage")
                    }
                }
            }
            for (entry in dueNow) {
                if (entry.reads < CONFIRM_DELAYS_MS.size) continue
                if (!remove(streamId, entry.id)) continue
                if (entry.verified) {
                    manager.markUndelivered(entry.id, UNDELIVERED_REASON)
                    Log.w(TAG, "$label: ${entry.id} never reached storage")
                } else {
                    Log.w(TAG, "$label: ${entry.id} unverifiable, storage never answered")
                }
            }
        }
        clear(streamId)
    }
}
