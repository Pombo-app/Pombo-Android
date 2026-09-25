package com.pombo.android.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * DMs this device sent, per conversation (web: secureStorage sentMessages).
 *
 * Not an optimisation — the only copy. A DM is published to the *peer's* inbox,
 * and inbox reads are owner-only, so your own outgoing messages can never come
 * back from a resend. Without persisting them here, reopening a conversation
 * shows only what the peer sent you.
 *
 * Encrypted at rest because it holds plaintext message bodies.
 */
class SentDmStore(context: Context) {

    private val prefs by lazy {
        com.pombo.android.core.SecurePrefs.create(context, "pombo_sent_dms")
    }

    @Volatile var scopeAddress: String? = null
    @Volatile var memoryOnly: Boolean = false

    /** Web CONFIG.dm.maxSentMessages — the sync merge caps at the same number. */
    private val maxPerConversation = 200

    private fun key(streamId: String): String {
        val scope = scopeAddress?.lowercase() ?: "none"
        return "sent_${scope}_$streamId"
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

    fun add(streamId: String, message: JSONObject) {
        if (memoryOnly) return
        val id = message.optString("id")
        if (id.isEmpty()) return
        val kept = load(streamId).filterNot { it.optString("id") == id } + message
        save(streamId, kept.sortedBy { it.optLong("timestamp") }.takeLast(maxPerConversation))
    }

    /**
     * Delete a sent message: drop it here and record the deletion, which the
     * sync carries to the account's other devices.
     */
    fun delete(streamId: String, messageId: String) {
        if (memoryOnly) return
        val deleted = deleted()
        val gone = deleted.optJSONObject(streamId) ?: JSONObject().also { deleted.put(streamId, it) }
        gone.put(messageId, System.currentTimeMillis())
        saveDeleted(deleted)
        save(streamId, load(streamId).filterNot { it.optString("id") == messageId })
    }

    /** Every recorded deletion, { streamId: { messageId: deletedAt } }. */
    fun deleted(): JSONObject {
        if (memoryOnly) return JSONObject()
        val raw = prefs.getString(deletedKey(), null) ?: return JSONObject()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    /** Joins synced deletions into the local record and drops what they name. */
    fun importDeleted(sentDeletedAt: JSONObject) {
        if (memoryOnly) return
        val deleted = com.pombo.android.core.SyncMerge.mergeSentDeletedAt(deleted(), sentDeletedAt)
        saveDeleted(deleted)
        sentDeletedAt.keys().forEach { streamId ->
            val gone = deleted.optJSONObject(streamId) ?: return@forEach
            val messages = load(streamId)
            val kept = messages.filterNot { gone.has(it.optString("id")) }
            if (kept.size != messages.size) save(streamId, kept)
        }
    }

    private fun deletedKey(): String = "deleted_${scopeAddress?.lowercase() ?: "none"}"

    private fun saveDeleted(deleted: JSONObject) {
        prefs.edit().putString(deletedKey(), deleted.toString()).apply()
    }

    /**
     * Drops the whole local half of a conversation.
     *
     * Destructive and unrecoverable — an outgoing DM exists nowhere else, so
     * this is only for blocking a peer, where the web does the same
     * (`clearSentMessages` in leaveChannel's block branch) and discarding is
     * the user's explicit intent.
     */
    fun clear(streamId: String) {
        if (memoryOnly) return
        prefs.edit().remove(key(streamId)).apply()
    }

    /**
     * Applies an edit in place so the stored copy matches what was published.
     * [at] is the edit's own timestamp: the sync keeps the latest edit.
     */
    fun edit(streamId: String, messageId: String, newText: String, at: Long) {
        if (memoryOnly) return
        save(streamId, load(streamId).map {
            if (it.optString("id") == messageId) {
                JSONObject(it.toString()).put("text", newText).put("_edited", true).put("_editedAt", at)
            } else it
        })
    }

    private fun save(streamId: String, messages: List<JSONObject>) {
        val arr = JSONArray()
        messages.forEach { arr.put(it) }
        prefs.edit().putString(key(streamId), arr.toString()).apply()
    }

    /** Every conversation's messages, in the shape the sync payload expects. */
    fun exportAll(streamIds: List<String>): JSONObject {
        val out = JSONObject()
        streamIds.forEach { id ->
            val msgs = load(id)
            if (msgs.isNotEmpty()) out.put(id, JSONArray().also { a -> msgs.forEach { a.put(it) } })
        }
        return out
    }

    /**
     * Folds a synced set into the local one.
     *
     * Union by id, never replace: this runs on every start, and overwriting
     * would delete every message sent since the snapshot was taken — the local
     * copy is the ONLY copy of an outgoing DM, so that loss is permanent.
     * Local wins a collision unless the incoming copy was edited later, and a
     * message recorded as deleted is never brought back.
     */
    fun importAll(sentMessages: JSONObject) {
        if (memoryOnly) return
        val deleted = deleted()
        sentMessages.keys().forEach { streamId ->
            val arr = sentMessages.optJSONArray(streamId) ?: return@forEach
            val merged = com.pombo.android.core.SyncMerge.mergeSentMessages(
                JSONObject().put(streamId, JSONArray().also { a -> load(streamId).forEach { a.put(it) } }),
                JSONObject().put(streamId, arr),
                deleted
            ).optJSONArray(streamId) ?: return@forEach
            save(streamId, (0 until merged.length()).mapNotNull { merged.optJSONObject(it) })
        }
    }
}
