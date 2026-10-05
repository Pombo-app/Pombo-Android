package com.pombo.android.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Text messages this device failed to send, per conversation: what brings a
 * "Not sent" bubble and its Retry back after a restart. Each entry holds the
 * wire fields the Retry republishes (id, text, sender, senderName, timestamp,
 * replyTo) plus `failError`, `undelivered` and `failedAt`.
 *
 * Device-local on purpose: never in the sync or the backup, or every device
 * of the account would offer a Retry for the same message.
 *
 * Encrypted at rest because it holds plaintext message bodies.
 */
class FailedOutboxStore(context: Context) {

    private val prefs by lazy {
        com.pombo.android.core.SecurePrefs.create(context, "pombo_failed_outbox")
    }

    @Volatile var scopeAddress: String? = null
    @Volatile var memoryOnly: Boolean = false

    private fun key(streamId: String): String {
        val scope = scopeAddress?.lowercase() ?: "none"
        return "failed_${scope}_$streamId"
    }

    fun load(streamId: String): List<JSONObject> {
        if (memoryOnly) return emptyList()
        val raw = prefs.getString(key(streamId), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Adds or replaces the entry with the same id; past the cap the oldest go. */
    fun put(streamId: String, entry: JSONObject) {
        if (memoryOnly) return
        val id = entry.optString("id")
        if (id.isEmpty()) return
        val kept = load(streamId).filterNot { it.optString("id") == id } + entry
        save(streamId, kept.sortedBy { it.optLong("timestamp") }.takeLast(MAX_PER_CONVERSATION))
    }

    fun remove(streamId: String, messageId: String) {
        if (memoryOnly) return
        val entries = load(streamId)
        val kept = entries.filterNot { it.optString("id") == messageId }
        if (kept.size != entries.size) save(streamId, kept)
    }

    fun clear(streamId: String) {
        if (memoryOnly) return
        prefs.edit().remove(key(streamId)).apply()
    }

    private fun save(streamId: String, entries: List<JSONObject>) {
        if (entries.isEmpty()) {
            prefs.edit().remove(key(streamId)).apply()
            return
        }
        val arr = JSONArray()
        entries.forEach { arr.put(it) }
        prefs.edit().putString(key(streamId), arr.toString()).apply()
    }

    companion object {
        const val MAX_PER_CONVERSATION = 20
    }
}
