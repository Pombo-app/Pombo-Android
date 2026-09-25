package com.pombo.android.core

import org.json.JSONObject

/**
 * Wire framing for a state snapshot too big to travel as one message: a run of
 * chunks closed by a manifest, the same shape image blobs use. The network
 * drops an oversized message WITHOUT an error the publisher can see.
 *
 * Web parity: syncChunks.js, locked by docs/SYNC-chunk-vectors.json and
 * docs/ADMIN-chunk-vectors.json.
 */
object SyncChunks {

    /**
     * Characters of cleartext per wire message. Sealing JSON-escapes the slice
     * and base64s the ciphertext, so the envelope lands well over this —
     * measured, a 150 KB slice reaches the wire at ~227 KB. Raising it is not
     * free, and the web must use the same number or a split push made here
     * would not reassemble there.
     */
    const val CHUNK_CHARS = 150 * 1024

    /**
     * The row types and fields of one framed protocol. [carry] names the
     * payload fields every row repeats; [keepPairs] never cuts between the
     * two halves of a surrogate pair, which a UTF-8 encoder turns into '?'.
     */
    class Frame(
        val chunk: String,
        val manifest: String,
        val id: String,
        val carry: List<String>,
        val keepPairs: Boolean
    )

    private val SYNC = Frame("sync_chunk", "sync_manifest", "syncId", listOf("ts"), keepPairs = false)

    /**
     * An ADMIN_STATE too big for one message. Its own row types, so a reader
     * that predates the split skips the rows instead of misreading them;
     * `rev` rides every row so a run can be ranked before it is assembled.
     */
    val ADMIN = Frame("admin_chunk", "admin_manifest", "runId", listOf("rev", "ts"), keepPairs = true)

    /** A run this reader could not use, and why — for the caller to log. */
    data class Dropped(val syncId: String, val have: Int, val want: Int, val reason: String)

    /** A complete run, with the manifest that closed it. */
    data class Joined(val runId: String, val manifest: JSONObject, val payload: JSONObject)

    /**
     * Frame a sync snapshot for the wire: the payload itself when it fits,
     * else its chunks followed by the manifest that closes the run.
     */
    fun split(
        payload: JSONObject,
        syncId: String,
        limit: Int = CHUNK_CHARS
    ): List<JSONObject> = splitFramed(payload, syncId, SYNC, limit)

    /** Frame a payload for the wire under [frame]. */
    fun splitFramed(payload: JSONObject, runId: String, frame: Frame, limit: Int): List<JSONObject> {
        val serialised = payload.toString()
        if (serialised.length <= limit) return listOf(payload)

        val slices = ArrayList<String>()
        var start = 0
        while (start < serialised.length) {
            var end = minOf(start + limit, serialised.length)
            if (frame.keepPairs && end < serialised.length && end - 1 > start &&
                Character.isHighSurrogate(serialised[end - 1])) end -= 1
            slices.add(serialised.substring(start, end))
            start = end
        }

        val chunkCount = slices.size
        fun row(type: String) = JSONObject().put("type", type).put("v", 1).also { r ->
            for (field in frame.carry) r.put(field, payload.optLong(field))
            r.put(frame.id, runId)
        }
        val out = ArrayList<JSONObject>(chunkCount + 1)
        for ((i, slice) in slices.withIndex()) {
            out.add(row(frame.chunk)
                .put("chunkIndex", i).put("chunkCount", chunkCount)
                .put("data", slice))
        }
        // Last, so a reader holding the manifest knows the run is complete
        // rather than still arriving.
        out.add(row(frame.manifest).put("chunkCount", chunkCount))
        return out
    }

    /**
     * Put the sync wire back together. A run missing any chunk is not half a
     * snapshot, it is none: its JSON would not parse, and applying a truncated
     * snapshot would merge garbage. Such runs are reported and dropped, and
     * the next push carries the state again.
     */
    fun reassemble(
        messages: List<JSONObject>,
        onDropped: (Dropped) -> Unit = {}
    ): List<JSONObject> {
        val whole = messages.filter { it.optInt("v") == 1 && it.optString("type") == "sync" }.toMutableList()
        for (run in joinFramed(messages, SYNC, onDropped)) {
            val count = run.manifest.optInt("chunkCount", 0)
            if (run.payload.optString("type") == "sync" && run.payload.optInt("v") == 1) whole.add(run.payload)
            else onDropped(Dropped(run.runId, count, count, "not a snapshot"))
        }
        return whole
    }

    /**
     * The complete runs of [frame] among [messages], in any order. Rows of
     * any other type are ignored; what the joined payload must be is the
     * caller's to check.
     */
    fun joinFramed(
        messages: List<JSONObject>,
        frame: Frame,
        onDropped: (Dropped) -> Unit = {}
    ): List<Joined> {
        val parts = HashMap<String, HashMap<Int, String>>()
        val manifests = HashMap<String, JSONObject>()

        for (m in messages) {
            if (m.optInt("v") != 1) continue
            val id = m.optString(frame.id)
            if (id.isEmpty()) continue
            when (m.optString("type")) {
                frame.chunk -> {
                    val data = m.optString("data")
                    val index = m.optInt("chunkIndex", -1)
                    if (data.isNotEmpty() && index >= 0) {
                        parts.getOrPut(id) { HashMap() }[index] = data
                    }
                }
                frame.manifest -> manifests[id] = m
            }
        }

        val out = ArrayList<Joined>()
        for ((runId, manifest) in manifests) {
            val count = manifest.optInt("chunkCount", 0)
            val got = parts[runId]
            if (count <= 0 || got == null || got.size != count) {
                onDropped(Dropped(runId, got?.size ?: 0, count, "incomplete"))
                continue
            }
            val joined = (0 until count).joinToString("") { got[it] ?: "" }
            val payload = try { JSONObject(joined) } catch (e: Exception) {
                onDropped(Dropped(runId, got.size, count, "unparseable")); continue
            }
            out.add(Joined(runId, manifest, payload))
        }
        return out
    }
}
