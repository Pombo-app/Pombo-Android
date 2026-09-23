package com.pombo.android.core.channels

import android.util.Log
import com.pombo.android.core.StorageEndpoints
import com.pombo.android.core.StreamConstants
import com.pombo.android.data.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * A storage provider holds only what is published after the stream is assigned
 * to it: one added to a live channel lacks the -4 key anchors and the -3 until
 * the owner publishes them again.
 */
class StorageCopy(private val scope: CoroutineScope, private val host: Host) {

    interface Host {
        fun account(): String?
        fun channel(messageStreamId: String): Channel?
        fun isOwner(channel: Channel): Boolean
        suspend fun ensureAdminLoaded(channel: Channel)
        fun adminRev(channel: Channel): Int
        suspend fun readImage(channel: Channel): JSONObject?
        /** Each publish returns the timestamp it went out with (0 when unknown). */
        suspend fun republishAnchors(channel: Channel): List<Long>
        suspend fun publishAdminState(channel: Channel): Long
        suspend fun republishImage(channel: Channel, payload: JSONObject): Long
        suspend fun publishPasswordChallenge(channel: Channel): Long
        suspend fun providers(streamId: String): List<StorageEndpoints.Provider>
        /** The timestamps among [timestamps] the provider holds; null when it did not answer. */
        suspend fun storedOn(
            provider: StorageEndpoints.Node, streamId: String, partition: Int, timestamps: List<Long>
        ): Set<Long>?
        fun loadPending(key: String): List<String>
        fun savePending(key: String, nodes: List<String>)
        fun notice(message: String, kind: Notice)
        suspend fun sleep(ms: Long)
    }

    enum class Notice { DONE, WARNING }

    data class Snapshot(val image: JSONObject?)

    enum class Outcome { PRESENT, UNVERIFIABLE, MISSING, GONE, FAILED }

    private data class Row(
        val item: String,
        val streamId: String = "",
        val partition: Int = 0,
        val timestamp: Long = 0L,
        val failed: Boolean = false
    )

    private val runs = ConcurrentHashMap<String, Deferred<Outcome>>()

    private fun ownedChannel(messageStreamId: String): Channel? {
        val channel = host.channel(messageStreamId) ?: return null
        if (channel.type == "dm") return null
        return if (host.isOwner(channel)) channel else null
    }

    private fun pendingKey(messageStreamId: String) = "storage-copy|${host.account()?.lowercase()}|$messageStreamId"

    fun pending(messageStreamId: String): List<String> = host.loadPending(pendingKey(messageStreamId))

    private fun setPending(messageStreamId: String, node: String, waiting: Boolean) {
        val nodes = LinkedHashSet(pending(messageStreamId))
        if (waiting) nodes.add(node) else nodes.remove(node)
        host.savePending(pendingKey(messageStreamId), nodes.toList())
    }

    /** Must run before the provider is added: once assigned, a read can land on it and find nothing. */
    suspend fun prepare(messageStreamId: String): Snapshot? {
        val channel = ownedChannel(messageStreamId) ?: return null
        runCatching { host.ensureAdminLoaded(channel) }
            .onFailure { Log.w(TAG, "admin state not loaded: ${it.message}") }
        return Snapshot(image = runCatching { host.readImage(channel) }.getOrNull())
    }

    fun copyTo(messageStreamId: String, nodeAddress: String, snapshot: Snapshot?): Deferred<Outcome> {
        val node = nodeAddress.lowercase()
        val key = "$messageStreamId|$node"
        runs[key]?.takeIf { it.isActive }?.let { return it }
        setPending(messageStreamId, node, true)
        val run = scope.async {
            try {
                copy(messageStreamId, node, snapshot)
            } catch (e: Exception) {
                Log.w(TAG, "storage copy stopped: ${e.message}")
                Outcome.FAILED
            } finally {
                runs.remove(key)
            }
        }
        runs[key] = run
        return run
    }

    fun resume(messageStreamId: String) {
        val nodes = pending(messageStreamId)
        if (nodes.isEmpty() || ownedChannel(messageStreamId) == null) return
        scope.launch {
            val snapshot = prepare(messageStreamId)
            nodes.forEach { copyTo(messageStreamId, it, snapshot) }
        }
    }

    private suspend fun copy(messageStreamId: String, node: String, snapshot: Snapshot?): Outcome {
        val label = "storage copy of ${messageStreamId.takeLast(20)} to ${node.take(10)}"
        var items = ITEMS.toSet()
        repeat(REPUBLISH_LIMIT + 1) {
            val channel = ownedChannel(messageStreamId) ?: run {
                setPending(messageStreamId, node, false)
                return Outcome.GONE
            }
            val published = publish(channel, snapshot, items)
            if (published.isEmpty()) {
                setPending(messageStreamId, node, false)
                Log.i(TAG, "$label: nothing to copy")
                return Outcome.PRESENT
            }
            for (delayMs in DELAYS_MS) {
                host.sleep(delayMs)
                val (missing, unverifiable) = lookUp(published, node)
                if (unverifiable) {
                    setPending(messageStreamId, node, false)
                    Log.w(TAG, "$label: the provider does not answer which rows it holds")
                    host.notice("The storage provider cannot confirm the copy. Keep the old one for now.", Notice.WARNING)
                    return Outcome.UNVERIFIABLE
                }
                if (missing.isEmpty()) {
                    setPending(messageStreamId, node, false)
                    Log.i(TAG, "$label confirmed")
                    host.notice("Channel keys and settings copied to the storage provider.", Notice.DONE)
                    return Outcome.PRESENT
                }
                items = missing
            }
            Log.w(TAG, "$label: still missing ${items.joinToString()}, publishing again")
        }
        Log.w(TAG, "$label never confirmed, kept pending")
        host.notice(
            "Could not confirm the copy on the storage provider yet. Keep the old one; it is retried when you open the channel.",
            Notice.WARNING
        )
        return Outcome.MISSING
    }

    private suspend fun publish(channel: Channel, snapshot: Snapshot?, items: Set<String>): List<Row> {
        val out = ArrayList<Row>()
        suspend fun attempt(item: String, block: suspend () -> List<Row>) {
            try {
                out += block()
            } catch (e: Exception) {
                Log.w(TAG, "storage copy: $item not published: ${e.message}")
                out += Row(item, failed = true)
            }
        }
        fun row(item: String, streamId: String, partition: Int, timestamp: Long) =
            if (timestamp > 0) Row(item, streamId, partition, timestamp) else Row(item, failed = true)

        if ("keys" in items && channel.type == "gated") {
            val keysStreamId = channel.keysStreamId.ifEmpty { StreamConstants.deriveKeysId(channel.messageStreamId) }
            attempt("keys") {
                host.republishAnchors(channel).map { row("keys", keysStreamId, StreamConstants.P_KEY_EXCHANGE, it) }
            }
        }
        if ("admin" in items && host.adminRev(channel) > 0) {
            attempt("admin") {
                listOf(row("admin", channel.adminStreamId, StreamConstants.ADMIN_MODERATION, host.publishAdminState(channel)))
            }
        }
        val image = snapshot?.image
        if ("image" in items && image != null
            && !(image.optBoolean("encrypted", false) && channel.password.isNullOrEmpty())) {
            attempt("image") {
                listOf(row("image", channel.adminStreamId, StreamConstants.ADMIN_CHANNEL_IMAGE,
                    host.republishImage(channel, image)))
            }
        }
        if ("password" in items && channel.type == "password" && !channel.password.isNullOrEmpty()) {
            attempt("password") {
                listOf(row("password", channel.adminStreamId, StreamConstants.ADMIN_PASSWORD_CHALLENGE,
                    host.publishPasswordChallenge(channel)))
            }
        }
        return out
    }

    /**
     * A provider that answers without the `stored` endpoint can never say which
     * rows it holds; one that does not answer only has them missing for now.
     */
    private suspend fun lookUp(published: List<Row>, node: String): Pair<Set<String>, Boolean> {
        val missing = published.filter { it.failed }.map { it.item }.toMutableSet()
        for ((key, rows) in published.filterNot { it.failed }.groupBy { it.streamId to it.partition }) {
            val (streamId, partition) = key
            val listed = runCatching { host.providers(streamId) }.getOrDefault(emptyList())
                .firstOrNull { it.nodeAddress.equals(node, ignoreCase = true) }
            if (listed != null && "stored" !in listed.features && listed.answered) return missing to true
            val provider = listed?.takeIf { "stored" in it.features }
            val present = provider?.let {
                host.storedOn(StorageEndpoints.Node(it.nodeAddress, it.urls), streamId, partition, rows.map { r -> r.timestamp })
            }
            rows.forEach { if (present == null || it.timestamp !in present) missing += it.item }
        }
        return missing to false
    }

    companion object {
        private const val TAG = "StorageCopy"
        private val ITEMS = listOf("keys", "admin", "image", "password")
        val DELAYS_MS = longArrayOf(5_000, 10_000, 20_000, 40_000)
        const val REPUBLISH_LIMIT = 2
    }
}
