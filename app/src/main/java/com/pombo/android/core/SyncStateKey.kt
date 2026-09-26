package com.pombo.android.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * The news a sync state carries, as one canonical string (web syncStateKey.js):
 * two states with the same key need no push between them.
 *
 * What is not news: caches, bookkeeping the network or the next real change
 * brings back, and stream ids derived from the channel's own id. Everything
 * else is, including a slice this client does not know. The web keeps the
 * same lists; the "publish" parity vectors in docs/SYNC-merge-vectors.json
 * lock them.
 */
object SyncStateKey {

    val IGNORED_SLICES = listOf("ensCache", "sliceTs")
    val IGNORED_EPOCH_KEY_FIELDS = listOf(
        "announces", "pendingRequests", "helloEpochs", "helloName", "helloTs",
        "seenRequesters", "pubAnnounce", "intAnnounce"
    )
    val IGNORED_CHANNEL_FIELDS = listOf(
        "ephemeralStreamId", "adminStreamId", "keysStreamId", "interactionsStreamId",
        "inboxStreamId", "storageProvider"
    )

    fun key(state: JSONObject): String {
        val projected = LinkedHashMap<String, Any?>()
        state.keys().forEach { slice ->
            if (slice in IGNORED_SLICES) return@forEach
            val value = state.opt(slice)
            projected[slice] = when {
                slice == "channels" && value is JSONArray ->
                    (0 until value.length()).map { without(value.opt(it), IGNORED_CHANNEL_FIELDS) }
                        .sortedBy { (it as? JSONObject)?.optString("messageStreamId") ?: "" }
                slice == "epochKeys" && value is JSONObject -> JSONObject().also { out ->
                    value.keys().forEach { id -> out.put(id, without(value.opt(id), IGNORED_EPOCH_KEY_FIELDS)) }
                }
                else -> value
            }
        }
        return StringBuilder().also { write(it, canonical(projected)) }.toString()
    }

    private fun without(record: Any?, fields: List<String>): Any? {
        if (record !is JSONObject) return record
        return JSONObject().also { out ->
            record.keys().forEach { k -> if (k !in fields) out.put(k, record.get(k)) }
        }
    }

    /** Sorted keys, and an empty entry (null, "", false, [], {}) dropped: it is the same state as a missing one. */
    private fun canonical(value: Any?): Any? = when (value) {
        is JSONObject -> canonicalMap(value.keys().asSequence().associateWith { value.opt(it) })
        is Map<*, *> -> canonicalMap(value.entries.associate { (k, v) -> k.toString() to v })
        is JSONArray -> (0 until value.length()).map { canonical(value.opt(it)) }
        is List<*> -> value.map { canonical(it) }
        JSONObject.NULL -> null
        else -> value
    }

    private fun canonicalMap(entries: Map<String, Any?>): Map<String, Any?> {
        val out = java.util.TreeMap<String, Any?>()
        entries.forEach { (k, v) -> canonical(v).takeUnless { isEmpty(it) }?.let { out[k] = it } }
        return out
    }

    private fun isEmpty(value: Any?): Boolean = when (value) {
        null -> true
        is String -> value.isEmpty()
        is Boolean -> !value
        is Map<*, *> -> value.isEmpty()
        is List<*> -> value.isEmpty()
        else -> false
    }

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    out.append(JSONObject.quote(k.toString())).append(':')
                    write(out, v)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, v -> if (i > 0) out.append(','); write(out, v) }
                out.append(']')
            }
            is String -> out.append(JSONObject.quote(value))
            null -> out.append("null")
            else -> out.append(value.toString())
        }
    }
}
