package com.pombo.android.core

import org.json.JSONObject

/**
 * Judging a stored message by the node's receive time.
 *
 * A message's `timestamp` is chosen by its publisher; `storedAt` is when a
 * Pombo storage node received it, the only instant the publisher did not
 * choose. The bridge attaches it to a resend entry's `meta` when the node
 * served one; this object mirrors the web's `storageFetch.judgeMessage`.
 */
object StoredAt {

    /** The instant to evaluate kid freshness by: storedAt when known, else the declared timestamp. */
    fun judgeTime(meta: JSONObject?): Long {
        val stored = meta?.optLong("storedAt", 0L) ?: 0L
        return if (stored > 0L) stored else meta?.optLong("timestamp", 0L) ?: 0L
    }

    /**
     * A message whose declared timestamp lies further in the future than the
     * node's receive time allows was planted to sit above the conversation.
     */
    fun forwardDated(meta: JSONObject?, toleranceMs: Long): Boolean {
        val stored = meta?.optLong("storedAt", 0L) ?: 0L
        val declared = meta?.optLong("timestamp", 0L) ?: 0L
        return stored > 0L && declared > 0L && declared - stored > toleranceMs
    }
}
