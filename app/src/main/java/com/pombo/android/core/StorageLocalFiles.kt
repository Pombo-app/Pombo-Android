package com.pombo.android.core

import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/**
 * On-disk record of the storage files this account holds on the device, so a
 * finished download, or the original of an upload, outlives the process. It
 * lives in the account's transfer dir, beside the bytes it points at.
 *
 * Same expiry as [SeedRegistry]. A received file is deleted with its entry; a
 * sent original is the user's own file, so its entry is only forgotten and the
 * caller releases the read grant.
 *
 * Writes are atomic (temp file + rename), as in [SeedRegistry].
 */
class StorageLocalFiles(private val dir: () -> File) {

    data class Entry(
        val transferId: String,
        /** A download's file, under the transfer dir. */
        val path: String?,
        /** An upload's original, as the content Uri it was picked from. */
        val uri: String?,
        val at: Long
    )

    companion object {
        private const val FILE_NAME = "storage-files.json"
    }

    private val lock = Any()
    private var cache: MutableMap<String, Entry>? = null

    private fun file() = File(dir(), FILE_NAME)

    private fun entries(): MutableMap<String, Entry> {
        cache?.let { return it }
        val loaded = LinkedHashMap<String, Entry>()
        try {
            val raw = file().takeIf { it.isFile }?.readText()
            if (raw != null) {
                val arr = JSONObject(raw).optJSONArray("files") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val tid = o.optString("transferId")
                    val path = o.optString("path").ifEmpty { null }
                    val uri = o.optString("uri").ifEmpty { null }
                    if (tid.isEmpty() || (path == null && uri == null)) continue
                    loaded[tid] = Entry(tid, path, uri, o.optLong("at", 0L))
                }
            }
        } catch (e: Exception) {
            // A corrupt record costs a re-download, not data: start empty.
        }
        cache = loaded
        return loaded
    }

    fun recordReceived(transferId: String, file: File, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        entries()[transferId] = Entry(transferId, file.path, null, now)
        persist()
    }

    fun recordSent(transferId: String, uri: String, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        entries()[transferId] = Entry(transferId, null, uri, now)
        persist()
    }

    /** The downloaded file, while it is still on disk. */
    fun received(transferId: String): File? = synchronized(lock) {
        entries()[transferId]?.path?.let(::File)?.takeIf { it.isFile }
    }

    fun sent(transferId: String): String? = synchronized(lock) { entries()[transferId]?.uri }

    /** Forgets what was read: the next call reads the directory [dir] points at now. */
    fun reload() = synchronized(lock) { cache = null }

    /**
     * Drops entries past [SeedRegistry.EXPIRE_MS], deleting the downloaded
     * files, and returns the forgotten originals' Uris for the caller to release.
     */
    fun sweepExpired(now: Long = System.currentTimeMillis()): List<String> = synchronized(lock) {
        val expired = entries().values.filter { now - it.at > SeedRegistry.EXPIRE_MS }
        if (expired.isEmpty()) return emptyList()
        expired.forEach { e ->
            e.path?.let {
                runCatching { File(it).delete() }
                StorageStagingStore.delete(dir(), e.transferId)
            }
            entries().remove(e.transferId)
        }
        persist()
        expired.mapNotNull { it.uri }
    }

    private fun persist() {
        val arr = JSONArray()
        for (e in entries().values) {
            arr.put(
                JSONObject()
                    .put("transferId", e.transferId)
                    .put("path", e.path ?: "")
                    .put("uri", e.uri ?: "")
                    .put("at", e.at)
            )
        }
        try {
            val target = file()
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(JSONObject().put("files", arr).toString())
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("rename failed")
            }
        } catch (e: Exception) {
            // Failing to persist must not fail the transfer that triggered it.
        }
    }
}
