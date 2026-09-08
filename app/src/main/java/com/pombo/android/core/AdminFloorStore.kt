package com.pombo.android.core

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * The last ADMIN_STATE this device applied, per (account, admin stream) — the
 * local moderation floor. A cold session seeds from it so a storage node that
 * serves an older or empty snapshot cannot roll bans, hides and pins back. It
 * only ever moves forward; a channel never seen has no entry.
 */
class AdminFloorStore(context: Context) {

    private val file = File(context.filesDir, "admin-floor.json")

    private val map: JSONObject = try {
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    } catch (e: Exception) {
        JSONObject()
    }

    @Synchronized
    fun get(key: String): JSONObject? = map.optJSONObject(key)

    @Synchronized
    fun put(key: String, value: JSONObject) {
        map.put(key, value)
        try { file.writeText(map.toString()) } catch (e: Exception) { /* best effort */ }
    }
}
