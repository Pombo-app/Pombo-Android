package com.pombo.android.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Last-message previews for the channel list and Explore cards — the web's
 * ChannelLatestMessageManager, which persists them so a cold start paints
 * previews immediately instead of waiting on a resend per channel.
 *
 * One file per account: a DM's key is the peer's inbox, so two accounts
 * talking to the same peer share the key, and previews hold decrypted text.
 */
/** How long a preview may be newer than storage before it stops being an echo. */
private const val ECHO_GRACE_MS = 5 * 60 * 1000L

class LatestMessageStore(context: Context) {

    /**
     * [sender] is the already-formatted fallback label (You / senderName / short
     * address). [senderAddress] is the raw address so the list can resolve an ENS
     * name AT RENDER TIME — ENS usually resolves after the preview is stored, and
     * a label baked in at fetch time would stay a raw address forever.
     */
    data class Preview(
        val sender: String,
        val text: String,
        val ts: Long,
        val senderAddress: String = ""
    )

    private val _previews = MutableStateFlow<Map<String, Preview>>(emptyMap())
    val previews: StateFlow<Map<String, Preview>> = _previews.asStateFlow()

    // Persistent, like the web's IndexedDB store — cacheDir gets purged.
    private val dir = File(context.filesDir, "channel-previews")
    // The device-wide file: its previews belong to no account, so nothing may read them.
    private val deviceWide = listOf(
        File(context.filesDir, "channel-previews.json"),
        File(context.filesDir, "channel-previews.json.tmp")
    )
    private var deviceWideDropped = false

    private val lock = Any()
    private var generation = 0

    /** The account whose previews these are; changing it empties memory until [warmUp]. */
    @Volatile var scopeAddress: String? = null
        set(value) = synchronized(lock) { field = value?.lowercase(); reset() }

    /** Guest: previews live in memory for the session, nothing reaches the disk. */
    @Volatile var memoryOnly: Boolean = false
        set(value) = synchronized(lock) { field = value; reset() }

    private fun reset() {
        generation++
        _previews.value = emptyMap()
        synchronized(inflight) { inflight.clear() }
    }

    private fun currentFile(): File? {
        val scope = scopeAddress
        if (memoryOnly || scope.isNullOrEmpty()) return null
        return File(dir, "$scope.json")
    }

    /** Captured before a fetch: a [put] carrying an older one belongs to another account. */
    fun generation(): Int = synchronized(lock) { generation }

    /**
     * In-flight de-duplication, matching the web manager: the sidebar and
     * Explore both ask for the same channel, and without this each surface
     * fires its own resend.
     */
    private val inflight = HashMap<String, kotlinx.coroutines.CompletableDeferred<Preview?>>()

    suspend fun dedup(streamId: String, fetch: suspend () -> Preview?): Preview? {
        val existing = synchronized(inflight) { inflight[streamId] }
        if (existing != null) return existing.await()
        val startedAt = generation()
        val deferred = kotlinx.coroutines.CompletableDeferred<Preview?>()
        synchronized(inflight) { inflight[streamId] = deferred }
        return try {
            val result = fetch()
            if (result != null) put(streamId, result, startedAt)
            deferred.complete(result)
            result
        } catch (e: Exception) {
            deferred.complete(null)
            null
        } finally {
            synchronized(inflight) { if (inflight[streamId] === deferred) inflight.remove(streamId) }
        }
    }

    suspend fun warmUp() = withContext(Dispatchers.IO) {
        val (startedAt, file) = synchronized(lock) {
            if (!deviceWideDropped) {
                deviceWideDropped = true
                deviceWide.forEach { runCatching { it.delete() } }
            }
            generation to currentFile()
        }
        if (file == null) return@withContext
        try {
            if (!file.exists()) return@withContext
            val root = JSONObject(file.readText())
            val loaded = HashMap<String, Preview>()
            root.keys().forEach { key ->
                val o = root.optJSONObject(key) ?: return@forEach
                val text = o.optString("text")
                // Reactions stopped being a preview line outside DMs, and a
                // channel with no new message would never replace the line it
                // was cached with. Dropping one wrongly costs a preview until
                // the next scan, which is seconds.
                if (!key.endsWith("/Pombo-DM-1") && text.startsWith("reacted with ")) return@forEach
                loaded[key] = Preview(
                    o.optString("sender"), text, o.optLong("ts"), o.optString("senderAddress")
                )
            }
            synchronized(lock) {
                // What arrived while the file was being read is newer than the file.
                if (startedAt == generation && loaded.isNotEmpty()) _previews.value = loaded + _previews.value
            }
        } catch (e: Exception) { /* corrupt cache — start empty */ }
    }

    fun cached(streamId: String): Preview? = _previews.value[streamId]

    suspend fun put(streamId: String, preview: Preview, startedAt: Int = generation()) {
        val (snapshot, file) = synchronized(lock) {
            if (startedAt != generation) return
            // Never let an older message overwrite a newer one (the live path in
            // channels.js always wins over a resend that lands late) — unless the
            // newer one has outlived what the stream serves, which is what pinned
            // a refused message on a read-only card for good.
            val existing = _previews.value[streamId]
            if (existing != null && existing.ts > preview.ts
                && System.currentTimeMillis() - existing.ts < ECHO_GRACE_MS) return
            _previews.value = _previews.value + (streamId to preview)
            _previews.value to currentFile()
        }
        if (file != null) persist(snapshot, file, startedAt)
    }

    /** Erases the previews of the account in scope (the account is being deleted). */
    suspend fun clearAccount(): Unit = withContext(Dispatchers.IO) {
        val file = synchronized(lock) { currentFile().also { reset() } } ?: return@withContext
        runCatching { file.delete() }
        runCatching { File(file.parentFile, file.name + ".tmp").delete() }
    }

    private suspend fun persist(snapshot: Map<String, Preview>, file: File, startedAt: Int) = withContext(Dispatchers.IO) {
        if (generation() != startedAt) return@withContext
        try {
            val root = JSONObject()
            snapshot.forEach { (k, v) ->
                root.put(k, JSONObject().put("sender", v.sender).put("text", v.text)
                    .put("ts", v.ts).put("senderAddress", v.senderAddress))
            }
            file.parentFile?.mkdirs()
            // Write-then-rename: a truncate-in-place interrupted mid-write
            // (process death, low battery kill) left a corrupt file behind.
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) file.writeText(root.toString())
        } catch (e: Exception) {
            android.util.Log.w("PomboStore", "preview persist failed: ${e.message}")
        }
    }
}
