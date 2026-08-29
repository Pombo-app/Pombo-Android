package com.pombo.android.core.channels

import org.json.JSONObject

/** Android optString returns the string "null" for JSON null — guard against that. */
internal fun JSONObject.optStringOrNull(key: String): String? {
    if (isNull(key)) return null
    val v = optString(key, "")
    return v.ifEmpty { null }
}
