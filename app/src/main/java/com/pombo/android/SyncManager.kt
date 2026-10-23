package com.pombo.android

import android.util.Log
import com.pombo.android.bridge.PomboBridge
import com.pombo.android.core.StreamConstants
import com.pombo.android.core.SyncMerge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cross-device state sync — port of the web's syncManager.js.
 *
 * State is published to the owner's own DM inbox on partition 1, encrypted to
 * self (ECDH against my own public key), so only this account can read it. The
 * inbox grants public PUBLISH but reads are owner-only, which is what makes it
 * a usable private mailbox.
 *
 * Payloads are versioned `{type:'sync', v:1, ts, data}` and merged
 * chronologically by [SyncMerge]; every applied `ts` is remembered so a stale
 * replica read cannot re-merge an old snapshot over newer state.
 */
class SyncManager(
    private val bridge: PomboBridge,
    private val scope: CoroutineScope,
    private val store: com.pombo.android.data.SyncStore,
    private val blobStore: com.pombo.android.core.ImageBlobStore,
    private val myAddress: () -> String?,
    private val myPrivateKey: () -> String?,
    private val isGuest: () -> Boolean,
    /** Snapshot of this device's state, in the web's payload shape. */
    private val exportLocal: () -> JSONObject,
    /** Applies merged state back into the local stores. */
    private val importMerged: (JSONObject) -> Unit,
    /**
     * Settings → Account. Read on every trigger rather than captured, so
     * changing the mode (or switching account, since the setting is scoped)
     * takes effect without rebuilding this manager.
     */
    private val syncMode: () -> com.pombo.android.data.SyncMode =
        { com.pombo.android.data.SyncMode.AUTOMATIC },
    /** Fired after a blob pull imports images, with their ids — lets the open
     *  channel swap "Loading image" placeholders for the pixels right away. */
    private val onBlobsImported: (List<String>) -> Unit = {},
    /** How often an open, foreground app asks storage whether another device pushed. */
    private val checkIntervalMs: Long = 60_000L,
    /** Storage re-reads after a push, measured from the publish. */
    private val confirmAtMs: LongArray = longArrayOf(5_000L, 10_000L, 20_000L, 40_000L),
    /** Blob pushes are not read back; the overlay is kept this long for them to leave. */
    private val blobLeaveAfterMs: Long = 30_000L
) {

    private val TAG = "PomboSync"

    /** Web default: 15s of quiet before an auto-push. */
    private val autoPushDelayMs = 15_000L

    /** A snapshot is a RUN of messages, so the window must hold several. */
    private val maxPayloads = 60

    /** A confirmed state is sent again after this long: storage keeps rows only for the inbox retention. */
    private val confirmedMaxAgeMs = 7L * 24 * 60 * 60 * 1000

    /** A push storage could not tell about is read again rather than resent, until it is this old. */
    private val unknownMaxAgeMs = 24L * 60 * 60 * 1000

    /** Read-back windows start this much before the push, for the publisher's clock. */
    private val readBackSkewMs = 60_000L

    private val ownRowsKept = 64

    @Volatile private var pulledRowTs = 0L
    @Volatile private var publishing = false
    private val ownRowKeys = LinkedHashSet<String>()
    private var confirmJob: Job? = null
    private var blobLeaveJob: Job? = null
    private var watchJob: Job? = null

    /** Every sync message has its own throwaway publisher, so its address names one row. */
    private fun rowKey(publisherId: String?) = (publisherId ?: "").lowercase()

    private enum class Verdict { CONFIRMED, ABSENT, UNKNOWN }

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _lastSyncTs = MutableStateFlow(store.lastSyncTs)
    val lastSyncTs: StateFlow<Long> = _lastSyncTs.asStateFlow()

    private var autoPushJob: Job? = null
    private var autoPushRetries = 0
    private var pushQueued = false
    /**
     * An auto push that has started publishing is never cancelled: a run cut
     * between its chunks stays on storage and never joins.
     */
    @Volatile private var autoPushPublishing = false
    /** The delay a change asked for while the auto push was publishing. */
    @Volatile private var queuedDelayMs: Long? = null
    /** Bumped on every change; a push clears `dirty` only if none came after its export. */
    private val changeSeq = java.util.concurrent.atomic.AtomicLong()
    /**
     * A pushed state counts as sent while its read-back runs. Only a confirmed
     * one is remembered across restarts: a push storage never kept has to go
     * out again.
     */
    @Volatile private var pendingHash: String? = null

    private fun inboxId(): String? = myAddress()?.lowercase()?.let { "$it/Pombo-DM-1" }

    /**
     * Marks local state dirty and schedules a push. Coalescing matters: every
     * message send and settings tweak would otherwise be its own publish.
     */
    fun scheduleAutoPush(delayMs: Long = autoPushDelayMs) {
        if (isGuest()) return
        // Dirty is recorded whatever the mode: "Manual only" defers the publish,
        // it does not discard the change. The next "Sync devices" still carries it.
        changeSeq.incrementAndGet()
        store.dirty = true
        if (syncMode() == com.pombo.android.data.SyncMode.MANUAL_ONLY) return
        if (autoPushPublishing) {
            queuedDelayMs = delayMs
            return
        }
        autoPushJob?.cancel()
        autoPushJob = scope.launch {
            delay(delayMs)
            val owner = myAddress()?.lowercase()
            autoPushPublishing = true
            val failed = try {
                if (pushSync() != null) autoPushRetries = 0
                false
            } catch (e: Exception) {
                true
            } finally {
                autoPushPublishing = false
            }
            val queued = queuedDelayMs
            queuedDelayMs = null
            if (owner == null || accountChanged(owner)) return@launch
            if (failed) {
                autoPushRetries++
                // Web: exponential backoff capped at five minutes.
                val retry = minOf(autoPushDelayMs * (1L shl autoPushRetries), 300_000L)
                scheduleAutoPush(retry)
            } else if (queued != null) {
                scheduleAutoPush(queued)
            }
        }
    }

    fun cancelAutoPush() {
        if (!autoPushPublishing) {
            autoPushJob?.cancel()
            autoPushJob = null
        }
        autoPushRetries = 0
        pushQueued = false
        queuedDelayMs = null
    }

    /** Publishes the local snapshot. Returns the payload timestamp, or null if skipped. */
    suspend fun pushSync(): Long? {
        if (isGuest()) return null
        if (_syncing.value) { pushQueued = true; return null }
        val me = myAddress()?.lowercase() ?: return null
        val key = myPrivateKey() ?: return null
        val inbox = "$me/Pombo-DM-1"
        if (!inboxExists()) return null

        _syncing.value = true
        try {
            val seq = changeSeq.get()
            if (accountChanged(me)) return null
            if (pendingHash == null && !settlePendingPush(inbox)) return null
            val data = exportLocal()
            val hash = stateHash(data)
            if (isConfirmedState(hash) || hash == pendingHash) {
                Log.d(TAG, "push skipped: state unchanged since the last push")
                if (changeSeq.get() == seq) store.dirty = false
                return null
            }
            val ts = System.currentTimeMillis()
            val payload = JSONObject()
                .put("type", "sync")
                .put("v", 1)
                .put("ts", ts)
                .put("data", data)

            // Sealed-to-self v2 under a throwaway publisher (web pushSync):
            // only this account's static key opens it, and the proof inside
            // recovers to this wallet — which the pull verifies.
            val messages = com.pombo.android.core.SyncChunks.split(
                payload, com.pombo.android.core.PomboCrypto.randomHex(8))
            Log.d(TAG, "push: ${payload.toString().length} B, " +
                "${payload.optJSONObject("data")?.optJSONArray("channels")?.length() ?: 0} channel(s), " +
                "${messages.size} message(s)")
            val sealed = messages.map { sealToSelf(it, key, me) }
            val publishers = sealed.map { it.publisherId }
            store.pendingPush = com.pombo.android.data.SyncStore.PendingPush(hash, publishers, ts)
            publishing = true
            try {
                for (message in sealed) {
                    if (accountChanged(me)) return null
                    publishSealed(inbox, StreamConstants.P_SYNC, message)
                }
            } finally {
                publishing = false
            }
            if (accountChanged(me)) return null
            noteOwnRows(publishers)
            confirmPush(inbox, hash, publishers, ts)

            // Our own snapshot is by definition already applied locally.
            store.recordApplied(listOf(ts))
            store.lastSyncTs = ts
            _lastSyncTs.value = ts
            if (changeSeq.get() == seq) store.dirty = false
            return ts
        } finally {
            _syncing.value = false
            if (pushQueued) { pushQueued = false; scheduleAutoPush(0L) }
        }
    }

    /**
     * A push started for one account stops when another takes over: what is
     * left of it, and the store it would write to, are no longer that account's.
     */
    private fun accountChanged(address: String): Boolean =
        (myAddress()?.lowercase() != address).also { if (it) Log.d(TAG, "push stopped: the account changed") }

    private fun stateHash(data: JSONObject): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(com.pombo.android.core.SyncStateKey.key(data).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** True when storage already holds this exact state from a recent push of ours. */
    private fun isConfirmedState(hash: String): Boolean =
        store.confirmedHash == hash && System.currentTimeMillis() - store.confirmedAt < confirmedMaxAgeMs

    private fun noteOwnRows(rows: List<String>) {
        ownRowKeys.addAll(rows)
        while (ownRowKeys.size > ownRowsKept) ownRowKeys.remove(ownRowKeys.first())
    }

    /**
     * Reads a push back from storage, then takes the node out of the sync
     * partition (web _confirmPush): the overlay is only needed to publish. A
     * push storage answered without leaves the state dirty to be sent again;
     * one storage never answered about stays pending, and the next push reads
     * it back before sending anything. Rows from another device met on the way
     * are pulled.
     */
    private fun confirmPush(inbox: String, hash: String, publishers: List<String>, sentAt: Long) {
        confirmJob?.cancel()
        pendingHash = hash
        confirmJob = scope.launch {
            var held = emptyList<Pair<Long, String>>()
            var verdict = Verdict.UNKNOWN
            var waited = 0L
            for (at in confirmAtMs) {
                delay(at - waited)
                waited = at
                held = readEnvelopes(inbox, StreamConstants.P_SYNC, sentAt) ?: continue
                verdict = verdictOf(held, publishers)
                if (verdict == Verdict.CONFIRMED) break
            }
            pendingHash = null
            val stillOurs = store.pendingPush?.publishers == publishers
            when (verdict) {
                Verdict.CONFIRMED -> {
                    store.confirmedHash = hash
                    store.confirmedAt = System.currentTimeMillis()
                    if (stillOurs) store.pendingPush = null
                    Log.d(TAG, "push confirmed by storage")
                }
                Verdict.ABSENT -> {
                    if (stillOurs) store.pendingPush = null
                    store.dirty = true
                    Log.w(TAG, "push not confirmed by storage, it will be sent again")
                }
                Verdict.UNKNOWN -> {
                    store.dirty = true
                    Log.w(TAG, "push unconfirmed: storage did not answer, it will be read back before any resend")
                }
            }
            val foreign = held.any { rowKey(it.second) !in ownRowKeys && it.first > pulledRowTs }
            // A push in flight re-joins the partition and leaves it when confirmed.
            if (!publishing) leaveStreamPart(inbox, StreamConstants.P_SYNC)
            if (foreign && syncMode() != com.pombo.android.data.SyncMode.MANUAL_ONLY) {
                val applied = try { pullSync() } catch (e: Exception) { 0 }
                if (applied > 0) try { pullImageBlobs() } catch (e: Exception) { /* next pull */ }
            }
        }
    }

    /**
     * Settles a push left pending (frozen in the background, killed, or never
     * answered about) before anything is published again: only a read that
     * answered without its rows lets the state go out a second time. Returns
     * false when storage could not tell and the push is still young enough to
     * wait for another read.
     */
    private suspend fun settlePendingPush(inbox: String): Boolean {
        val pending = store.pendingPush ?: return true
        val held = readEnvelopes(inbox, StreamConstants.P_SYNC, pending.at)
        when (if (held == null) Verdict.UNKNOWN else verdictOf(held, pending.publishers)) {
            Verdict.CONFIRMED -> {
                store.confirmedHash = pending.hash
                store.confirmedAt = System.currentTimeMillis()
                store.pendingPush = null
                noteOwnRows(pending.publishers)
                Log.d(TAG, "earlier push confirmed by storage")
            }
            Verdict.ABSENT -> {
                store.pendingPush = null
                Log.w(TAG, "earlier push not on storage, its state may be sent again")
            }
            Verdict.UNKNOWN -> {
                if (System.currentTimeMillis() - pending.at < unknownMaxAgeMs) {
                    Log.w(TAG, "earlier push still unconfirmed and storage did not answer: not sending again yet")
                    return false
                }
                store.pendingPush = null
                Log.w(TAG, "earlier push unconfirmed for a day, its state may be sent again")
            }
        }
        return true
    }

    private fun verdictOf(held: List<Pair<Long, String>>, publishers: Collection<String>): Verdict =
        if (held.map { rowKey(it.second) }.containsAll(publishers)) Verdict.CONFIRMED else Verdict.ABSENT

    /** (timestamp, publisher) of every row stored since [since]; null when storage did not answer. */
    private suspend fun readEnvelopes(inbox: String, partition: Int, since: Long): List<Pair<Long, String>>? = try {
        val res = bridge.call("resendEnvelopes", JSONObject()
            .put("streamId", inbox)
            .put("partition", partition)
            .put("from", since - readBackSkewMs)
            .put("to", System.currentTimeMillis() + readBackSkewMs), 60_000)
        if (res.optJSONObject("readError") != null) null
        else res.optJSONArray("rows")?.let { rows ->
            (0 until rows.length()).mapNotNull { i ->
                rows.optJSONObject(i)?.let { it.optLong("timestamp") to it.optString("publisherId") }
            }
        }
    } catch (e: Exception) {
        null
    }

    /** (timestamp, publisher) of a partition's newest rows; null when the read failed. */
    private suspend fun readRows(inbox: String, partition: Int, last: Int): List<Pair<Long, String>>? = try {
        val res = bridge.call("resend", JSONObject()
            .put("streamId", inbox)
            .put("partition", partition)
            .put("last", last), 60_000)
        val arr = res.optJSONArray("messages") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.optJSONObject("meta")?.let { it.optLong("timestamp") to it.optString("publisherId") }
        }
    } catch (e: Exception) {
        null
    }

    private suspend fun leaveStreamPart(inbox: String, partition: Int) {
        try {
            val res = bridge.call("leaveStreamPart", JSONObject()
                .put("streamId", inbox)
                .put("partition", partition))
            Log.d(TAG, "left sync partition $partition: $res")
        } catch (e: Exception) {
            Log.d(TAG, "leaving sync partition $partition failed: ${e.message}")
        }
    }

    /**
     * Pulls when storage holds a row from another device newer than anything
     * read here (web checkForNewSnapshot): one `last: 1` read, no overlay, since
     * the last row of a chunked push is its small manifest. Returns payloads
     * applied.
     */
    suspend fun checkForNewSnapshot(): Int {
        if (isGuest() || _syncing.value) return 0
        if (syncMode() == com.pombo.android.data.SyncMode.MANUAL_ONLY) return 0
        val inbox = inboxId() ?: return 0
        val newest = readRows(inbox, StreamConstants.P_SYNC, 1)?.maxByOrNull { it.first } ?: return 0
        if (rowKey(newest.second) in ownRowKeys || newest.first <= pulledRowTs) return 0
        return pullSync()
    }

    /** Checks every [checkIntervalMs] until stopped; run it only while the app is in the foreground. */
    fun startSnapshotWatch() {
        watchJob?.cancel()
        watchJob = scope.launch {
            var tickStart = System.currentTimeMillis()
            while (true) {
                delay((tickStart + checkIntervalMs - System.currentTimeMillis()).coerceAtLeast(0L))
                tickStart = System.currentTimeMillis()
                try {
                    if (checkForNewSnapshot() > 0) pullImageBlobs()
                } catch (e: Exception) {
                    Log.d(TAG, "snapshot check failed: ${e.message}")
                }
            }
        }
    }

    fun stopSnapshotWatch() {
        watchJob?.cancel()
        watchJob = null
    }

    /** On an account switch: a confirmation still running would act on the next account. */
    fun cancelPushConfirmation() {
        confirmJob?.cancel()
        confirmJob = null
        pendingHash = null
        blobLeaveJob?.cancel()
        blobLeaveJob = null
        pulledRowTs = 0L
        ownRowKeys.clear()
    }

    /** Fetches remote snapshots, merges them into local state. Returns payloads applied. */
    suspend fun pullSync(): Int {
        if (isGuest()) return 0
        if (_syncing.value) return 0
        val inbox = inboxId() ?: return 0
        val me = myAddress()?.lowercase() ?: return 0
        if (!inboxExists()) return 0

        _syncing.value = true
        try {
            var payloads = fetchPayloads(inbox, me)
            val applied = store.appliedTs()
            var fresh = payloads.filter { it.optLong("ts") !in applied }

            if (fresh.isEmpty() && payloads.isNotEmpty()) {
                val newestFetched = payloads.maxOf { it.optLong("ts") }
                val newestApplied = applied.maxOrNull() ?: 0L
                // Provably stale read — storage replicas are round-robined, so
                // one retry usually lands on the other replica.
                if (newestFetched < newestApplied) {
                    payloads = fetchPayloads(inbox, me)
                    fresh = payloads.filter { it.optLong("ts") !in applied }
                }
            }
            if (fresh.isEmpty()) return 0

            val ordered = fresh.sortedBy { it.optLong("ts") }
            val merged = SyncMerge.mergeSeries(
                exportLocal(),
                ordered.mapNotNull { it.optJSONObject("data") }
            )
            importMerged(merged)
            store.recordApplied(ordered.map { it.optLong("ts") })
            _lastSyncTs.value = ordered.last().optLong("ts")
            store.lastSyncTs = _lastSyncTs.value
            return ordered.size
        } finally {
            _syncing.value = false
        }
    }

    /**
     * Manual sync (the "Sync devices" button). Push-first like the web's
     * smartSync — the other devices must see this one's tombstones before a
     * pull can re-merge what was just left behind. Every phase runs even if an
     * earlier one failed (a dead pull must not silence the push flush); the
     * first error is rethrown at the end so the UI still reports failure.
     */
    suspend fun fullSync(): Int {
        var phaseError: Exception? = null
        try { pushSync() } catch (e: Exception) { phaseError = e; Log.w(TAG, "fullSync push failed: ${e.message}") }
        val applied = try { pullSync() } catch (e: Exception) { phaseError = phaseError ?: e; Log.w(TAG, "fullSync pull failed: ${e.message}"); 0 }
        try { pullImageBlobs() } catch (e: Exception) { phaseError = phaseError ?: e; Log.w(TAG, "fullSync blob pull failed: ${e.message}") }
        try { pushImageBlobs() } catch (e: Exception) { phaseError = phaseError ?: e; Log.w(TAG, "fullSync blob push failed: ${e.message}") }
        Log.d(TAG, "fullSync done: applied=$applied error=${phaseError?.message ?: "none"}")
        phaseError?.let { throw it }
        return applied
    }

    /**
     * Lifecycle-triggered sync (bridge connect, app foreground). Unlike the
     * manual [fullSync] this is built to be cheap enough to run unprompted:
     * - a foreground run pulls only when storage holds something newer
     *   ([checkForNewSnapshot]); connect is forced to a full pull, since it is
     *   rare and is exactly when state is stalest;
     * - push runs only when local state is actually dirty — but always BEFORE
     *   the pull, so a pending leave/block cannot be resurrected by the merge;
     * - blob pull (the expensive resend) always runs on a forced sync (once
     *   per connect); foreground ticks only run it when the state pull applied
     *   something new — a blob pushed by a device whose state was already in
     *   step would otherwise never arrive until the next manual sync. Blob
     *   push rides along whenever the local ledger holds unsynced images,
     *   a check that costs nothing.
     * Phases are isolated: this never throws, it reports and moves on.
     */
    suspend fun autoSync(force: Boolean = false): Int {
        if (isGuest()) return 0
        // The one gate that covers every unprompted path — the connect run, the
        // foreground tick, and anything added later. Only [fullSync], which the
        // "Sync devices" button calls, bypasses it.
        if (syncMode() == com.pombo.android.data.SyncMode.MANUAL_ONLY) {
            Log.d(TAG, "autoSync skipped (manual only)")
            return 0
        }
        if (_syncing.value) return 0
        if (store.dirty) {
            // The debounced auto-push would only republish the same snapshot.
            cancelAutoPush()
            try { pushSync() } catch (e: Exception) { Log.w(TAG, "autoSync push failed (dirty kept): ${e.message}") }
        }
        val applied = try {
            if (force) pullSync() else checkForNewSnapshot()
        } catch (e: Exception) {
            Log.w(TAG, "autoSync pull failed: ${e.message}"); 0
        }
        try { pushImageBlobs() } catch (e: Exception) { Log.w(TAG, "autoSync blob push failed: ${e.message}") }
        if (force || applied > 0) {
            try { pullImageBlobs() } catch (e: Exception) { Log.w(TAG, "autoSync blob pull failed: ${e.message}") }
        }
        Log.d(TAG, "autoSync done: force=$force applied=$applied")
        return applied
    }

    /**
     * Immediate flush of pending local changes — app going to background. The
     * 15s debounce dies with the process; the web does the same forcePushNow on
     * pagehide/visibilitychange. On failure `dirty` stays set, so the next
     * [autoSync] on connect finishes the job.
     */
    fun flushIfDirty() {
        if (isGuest() || !store.dirty) return
        // Backgrounding is still an unprompted publish. `dirty` survives, so the
        // next manual run carries the change.
        if (syncMode() == com.pombo.android.data.SyncMode.MANUAL_ONLY) return
        if (autoPushPublishing) {
            queuedDelayMs = 0L
            return
        }
        cancelAutoPush()
        scope.launch {
            try {
                pushSync()
            } catch (e: Exception) {
                Log.w(TAG, "background push failed, dirty kept: ${e.message}")
            }
        }
    }

    // ==================== image blobs (partition 2) ====================

    private val blobChunkChars = com.pombo.android.core.SyncChunks.CHUNK_CHARS

    /** Publishes locally-held images that no device has synced yet. */
    suspend fun pushImageBlobs() {
        if (isGuest() || _syncing.value) return
        val me = myAddress()?.lowercase() ?: return
        val key = myPrivateKey() ?: return
        val inbox = "$me/Pombo-DM-1"
        val pending = blobStore.unsynced()
        if (pending.isEmpty()) return
        if (!inboxExists()) return

        // Sealed-to-self v2 under a throwaway publisher, one seal per message.
        suspend fun publish(payload: JSONObject) {
            sealPublishToSelf(inbox, StreamConstants.P_SYNC_BLOBS, payload, key, me)
        }

        var published = 0
        for (record in pending) {
            if (accountChanged(me)) break
            val data = blobStore.load(record.imageId)
            if (data.isNullOrEmpty()) {
                // The blob is gone but the ledger still lists it; drop the entry
                // rather than retrying it on every sync forever.
                blobStore.forget(record.imageId)
                continue
            }
            try {
                if (data.length <= blobChunkChars) {
                    publish(JSONObject()
                        .put("type", "sync_blob").put("v", 2)
                        .put("ts", System.currentTimeMillis())
                        .put("imageId", record.imageId)
                        .put("streamId", record.streamId)
                        .put("data", data))
                } else {
                    val chunkCount = (data.length + blobChunkChars - 1) / blobChunkChars
                    for (i in 0 until chunkCount) {
                        val slice = data.substring(
                            i * blobChunkChars,
                            minOf((i + 1) * blobChunkChars, data.length)
                        )
                        publish(JSONObject()
                            .put("type", "sync_blob_chunk").put("v", 2)
                            .put("ts", System.currentTimeMillis())
                            .put("imageId", record.imageId)
                            .put("streamId", record.streamId)
                            .put("chunkIndex", i)
                            .put("chunkCount", chunkCount)
                            .put("data", slice))
                    }
                    publish(JSONObject()
                        .put("type", "sync_blob_manifest").put("v", 2)
                        .put("ts", System.currentTimeMillis())
                        .put("imageId", record.imageId)
                        .put("streamId", record.streamId)
                        .put("chunkCount", chunkCount)
                        .put("totalLength", data.length))
                }
                blobStore.markSynced(record.imageId)
                published++
            } catch (e: Exception) {
                // Leave it unsynced so the next run retries this one blob.
                Log.w(TAG, "blob push failed for ${record.imageId}: ${e.message}")
            }
        }
        Log.d(TAG, "blob push: ${pending.size} pending processed")
        if (published > 0) {
            blobLeaveJob?.cancel()
            blobLeaveJob = scope.launch {
                delay(blobLeaveAfterMs)
                leaveStreamPart(inbox, StreamConstants.P_SYNC_BLOBS)
            }
        }
    }

    /**
     * Imports blobs other devices pushed. Two passes, because a resend does not
     * guarantee chronological order and chunks published back-to-back can share
     * a millisecond — resolving manifests inline would miss chunks that arrive
     * after their own manifest.
     */
    suspend fun pullImageBlobs(): Int {
        if (isGuest() || _syncing.value) return 0
        val inbox = inboxId() ?: return 0
        val me = myAddress()?.lowercase() ?: return 0
        if (!inboxExists()) return 0

        val res = bridge.call("resend", JSONObject()
            .put("streamId", inbox)
            .put("partition", StreamConstants.P_SYNC_BLOBS)
            // Chunked images burn one message per ~150KB, so a small window
            // would cut off older chunks and leave assembly permanently short.
            .put("last", 200), 120_000)
        val arr = res.optJSONArray("messages") ?: JSONArray()

        val singles = mutableListOf<JSONObject>()
        val manifests = mutableListOf<JSONObject>()
        val chunks = HashMap<String, MutableMap<Int, String>>()
        val chunkStream = HashMap<String, String>()

        // Same rule as fetchPayloads: opening proves addressed-to-me, the
        // proof-recovered sender == me proves it is OURS.
        val opened = openAllSealed(arr)
        for (i in 0 until arr.length()) {
            val o = opened?.takeIf { !it.isNull(i) }?.optJSONObject(i) ?: continue
            if (!o.optString("sender").equals(me, ignoreCase = true)) continue
            val payload = o.optJSONObject("message") ?: continue
            if (payload.optInt("v") != 2) continue
            when (payload.optString("type")) {
                "sync_blob" -> singles.add(payload)
                "sync_blob_manifest" -> manifests.add(payload)
                "sync_blob_chunk" -> {
                    val id = payload.optString("imageId")
                    if (id.isEmpty()) continue
                    chunks.getOrPut(id) { HashMap() }[payload.optInt("chunkIndex")] = payload.optString("data")
                    payload.optString("streamId").takeIf { it.isNotEmpty() }?.let { chunkStream[id] = it }
                }
            }
        }

        val importedIds = mutableListOf<String>()
        var incomplete = 0
        for (p in singles) {
            val id = p.optString("imageId")
            if (id.isEmpty()) continue
            if (blobStore.save(id, p.optString("streamId"), p.optString("data"), synced = true)) importedIds.add(id)
        }
        for (m in manifests) {
            val id = m.optString("imageId")
            if (id.isEmpty()) continue
            val parts = chunks.remove(id) ?: continue
            val expected = m.optInt("chunkCount")
            if (parts.size != expected) { incomplete++; continue }  // wait for a later pull
            val assembled = buildString {
                for (i in 0 until expected) append(parts[i] ?: return@buildString)
            }
            val total = m.optInt("totalLength")
            if (total > 0 && assembled.length != total) { incomplete++; continue }
            if (blobStore.save(id, m.optString("streamId").ifEmpty { chunkStream[id] ?: "" }, assembled, synced = true)) {
                importedIds.add(id)
            }
        }
        Log.d(TAG, "blob pull: ${arr.length()} message(s), imported=${importedIds.size}, incomplete=$incomplete")
        if (importedIds.isNotEmpty()) {
            // A placeholder on screen ("Loading image") whose blob just landed
            // would otherwise stay a placeholder until the chat is reopened.
            onBlobsImported(importedIds)
        }
        return importedIds.size
    }

    private suspend fun fetchPayloads(inbox: String, me: String): List<JSONObject> {
        val res = bridge.call("resend", JSONObject()
            .put("streamId", inbox)
            .put("partition", StreamConstants.P_SYNC)
            .put("last", maxPayloads), 60_000)
        val arr = res.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val ts = arr.optJSONObject(i)?.optJSONObject("meta")?.optLong("timestamp") ?: 0L
            if (ts > pulledRowTs) pulledRowTs = ts
        }
        // The old "publisherId == me" gate is trap 1 of the migration: sealed
        // pushes ride a throwaway publisher, so that filter rejected every one
        // of our own payloads. Opening decides ownership instead — but opening
        // alone only proves the payload was ADDRESSED to us (anyone can seal
        // to a public key), so the proof-recovered sender must ALSO be this
        // wallet, or a stranger could inject state into our merge. (The web
        // currently skips that second check — flagged to be fixed there.)
        val opened = openAllSealed(arr)
        val mine = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            val o = opened?.takeIf { !it.isNull(i) }?.optJSONObject(i) ?: continue
            if (!o.optString("sender").equals(me, ignoreCase = true)) continue
            o.optJSONObject("message")?.let { mine.add(it) }
        }
        return com.pombo.android.core.SyncChunks.reassemble(mine) { dropped ->
            Log.w(TAG, "sync run ${dropped.syncId} dropped " +
                "(${dropped.have}/${dropped.want}, ${dropped.reason})")
        }
    }

    /** Native sealed open over a resend page; null-per-entry for anything not v2-sealed or not ours. */
    private suspend fun openAllSealed(arr: JSONArray): JSONArray? {
        val myPk = myPrivateKey() ?: return null
        val me = myAddress() ?: return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val content = arr.optJSONObject(i)?.opt("content")
                val opened =
                    if (content is JSONObject && content.optInt("v") == 2 && content.has("epk"))
                        com.pombo.android.core.SealedSenderCrypto.open(content, myPk, me)
                    else null
                out.put(
                    opened?.let { JSONObject().put("sender", it.first).put("message", it.second) }
                        ?: JSONObject.NULL
                )
            }
            out
        }
    }

    /**
     * Sealed-to-self v2, sealed natively — the identity key never enters the
     * WebView; the throwaway key crosses because it is also the publishing
     * identity (bridge publishAs).
     */
    private suspend fun sealPublishToSelf(
        streamId: String,
        partition: Int,
        payload: JSONObject,
        privateKey: String,
        address: String
    ): JSONObject = publishSealed(streamId, partition, sealToSelf(payload, privateKey, address))

    /** A message sealed to self, and the throwaway identity that will publish it. */
    private class Sealed(val envelope: JSONObject, val ephemeralKey: String, val publisherId: String)

    private suspend fun sealToSelf(payload: JSONObject, privateKey: String, address: String): Sealed {
        val recipientPub = com.pombo.android.core.EthereumSigner.compressedPublicKey(privateKey)
        val (envelope, ephemeralPk) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            com.pombo.android.core.SealedSenderCrypto.seal(payload, privateKey, address, recipientPub)
        }
        // The ceiling applies to the envelope, not the payload: JSON-escaping
        // the slice and base64 of the ciphertext both inflate it. Measured, a
        // 150 KB slice lands near 227 KB.
        val wire = envelope.toString().length
        if (wire > WIRE_WARN_BYTES) Log.w(TAG, "wire message $wire B — near the network ceiling")
        return Sealed(envelope, ephemeralPk, com.pombo.android.core.EthereumSigner.address(ephemeralPk).lowercase())
    }

    private suspend fun publishSealed(streamId: String, partition: Int, sealed: Sealed): JSONObject =
        bridge.call("publishAs", JSONObject()
            .put("streamId", streamId)
            .put("partition", partition)
            .put("content", sealed.envelope)
            .put("privateKey", sealed.ephemeralKey), 60_000)

    private suspend fun inboxExists(): Boolean = try {
        val me = myAddress() ?: return false
        bridge.call("getPeerPublicKey", JSONObject().put("address", me))
            .optString("publicKey").let { it.isNotEmpty() && it != "null" }
    } catch (e: Exception) {
        false
    }

    companion object {

        /** Above this an envelope is close enough to the ceiling to say so. */
        const val WIRE_WARN_BYTES = 235 * 1024
    }
}
