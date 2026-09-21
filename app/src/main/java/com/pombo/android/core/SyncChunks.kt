package com.pombo.android.core

import org.json.JSONObject

/**
 * Wire framing for a state snapshot too big to travel as one message: a run of
 * chunks closed by a manifest, the same shape image blobs use. The network
 * drops an oversized message WITHOUT an error the publisher can see.
 *
 * Web parity: syncChunks.js, locked by docs/SYNC-chunk-vectors.json.
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

    /** A run this reader could not use, and why — for the caller to log. */
    data class Dropped(val syncId: String, val have: Int, val want: Int, val reason: String)

    /**
     * Frame a payload for the wire: the payload itself when it fits, else its
     * chunks followed by the manifest that closes the run.
     */
    fun split(
        payload: JSONObject,
        syncId: String,
        limit: Int = CHUNK_CHARS
    ): List<JSONObject> {
        val serialised = payload.toString()
        if (serialised.length <= limit) return listOf(payload)

        val ts = payload.optLong("ts")
        val chunkCount = (serialised.length + limit - 1) / limit
        val out = ArrayList<JSONObject>(chunkCount + 1)
        for (i in 0 until chunkCount) {
            out.add(JSONObject()
                .put("type", "sync_chunk").put("v", 1)
                .put("ts", ts).put("syncId", syncId)
                .put("chunkIndex", i).put("chunkCount", chunkCount)
                .put("data", serialised.substring(
                    i * limit, minOf((i + 1) * limit, serialised.length))))
        }
        // Last, so a reader holding the manifest knows the run is complete
        // rather than still arriving.
        out.add(JSONObject()
            .put("type", "sync_manifest").put("v", 1)
            .put("ts", ts).put("syncId", syncId)
            .put("chunkCount", chunkCount))
        return out
    }

    /**
     * Put the wire back together. A run missing any chunk is not half a
     * snapshot, it is none: its JSON would not parse, and applying a truncated
     * snapshot would merge garbage. Such runs are reported and dropped, and
     * the next push carries the state again.
     */
    fun reassemble(
        messages: List<JSONObject>,
        onDropped: (Dropped) -> Unit = {}
    ): List<JSONObject> {
        val whole = mutableListOf<JSONObject>()
        val parts = HashMap<String, HashMap<Int, String>>()
        val expected = HashMap<String, Int>()

        for (m in messages) {
            if (m.optInt("v") != 1) continue
            when (m.optString("type")) {
                "sync" -> whole.add(m)
                "sync_chunk" -> {
                    val id = m.optString("syncId")
                    val data = m.optString("data")
                    val index = m.optInt("chunkIndex", -1)
                    if (id.isNotEmpty() && data.isNotEmpty() && index >= 0) {
                        parts.getOrPut(id) { HashMap() }[index] = data
                    }
                }
                "sync_manifest" -> {
                    val id = m.optString("syncId")
                    if (id.isNotEmpty()) expected[id] = m.optInt("chunkCount", 0)
                }
            }
        }

        for ((syncId, count) in expected) {
            val got = parts[syncId]
            if (count <= 0 || got == null || got.size != count) {
                onDropped(Dropped(syncId, got?.size ?: 0, count, "incomplete"))
                continue
            }
            val joined = (0 until count).joinToString("") { got[it] ?: "" }
            val payload = try { JSONObject(joined) } catch (e: Exception) {
                onDropped(Dropped(syncId, got.size, count, "unparseable")); continue
            }
            if (payload.optString("type") == "sync" && payload.optInt("v") == 1) whole.add(payload)
            else onDropped(Dropped(syncId, got.size, count, "not a snapshot"))
        }
        return whole
    }
}
