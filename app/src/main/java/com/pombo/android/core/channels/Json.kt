package com.pombo.android.core.channels

import org.json.JSONObject

/** Android optString returns the string "null" for JSON null — guard against that. */
internal fun JSONObject.optStringOrNull(key: String): String? {
    if (isNull(key)) return null
    val v = optString(key, "")
    return v.ifEmpty { null }
}

/**
 * The time the UI orders, pages and dates a message by. The signed envelope
 * time (`_timestamp`, stamped at ingest) is authoritative and unforgeable; the
 * publisher-chosen payload `timestamp` is only the fallback for a message with
 * no envelope yet (an own optimistic send). Ordering by the payload let a
 * past-dated message poison the pagination anchor and fake its position.
 */
internal fun JSONObject.messageTime(): Long {
    val env = optLong("_timestamp", 0L)
    return if (env > 0) env else optLong("timestamp", 0L)
}
