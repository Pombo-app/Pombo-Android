package com.pombo.android.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local map of notification tag -> channel, and the last message timestamp we
 * have already notified about.
 *
 * This is the Android counterpart of the service worker's IndexedDB `channels`
 * store (sw.js), and it exists for one reason: the push carries *only* a 1-byte
 * tag. With 256 possible tags many channels collide by design — that collision
 * is what buys K-anonymity from the relay — so most pushes that arrive are for
 * somebody else's channel. Without this table there is no way to tell.
 */
class PushRegistry(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("pombo_push", Context.MODE_PRIVATE)

    data class Entry(
        val streamId: String,
        val tag: String,
        val type: String,
        val name: String,
        val lastTimestamp: Long
    )

    @Volatile var scopeAddress: String? = null

    private fun key(): String =
        if (scopeAddress.isNullOrEmpty()) KEY else "${KEY}_${scopeAddress!!.lowercase()}"

    fun all(): List<Entry> = try {
        val arr = JSONArray(prefs.getString(key(), null) ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val streamId = o.optString("streamId")
            if (streamId.isEmpty()) return@mapNotNull null
            Entry(
                streamId = streamId,
                tag = o.optString("tag"),
                type = o.optString("type", "public"),
                name = o.optString("name", streamId),
                lastTimestamp = o.optLong("lastTimestamp", 0L)
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Tag lookup is the first filter: an unknown tag is noise, not our channel.
     * EVERY match is returned: one byte of tag collides across this user's own
     * channels by design, so a single answer is never the whole answer.
     */
    fun entriesByTag(tag: String): List<Entry> = all().filter { it.tag.equals(tag, ignoreCase = true) }

    fun isSubscribed(streamId: String): Boolean = all().any { it.streamId == streamId }

    private fun save(entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach {
            arr.put(
                JSONObject()
                    .put("streamId", it.streamId)
                    .put("tag", it.tag)
                    .put("type", it.type)
                    .put("name", it.name)
                    .put("lastTimestamp", it.lastTimestamp)
            )
        }
        prefs.edit().putString(key(), arr.toString()).apply()
    }

    fun add(entry: Entry) {
        save(all().filterNot { it.streamId == entry.streamId } + entry)
    }

    fun remove(streamId: String) {
        save(all().filterNot { it.streamId == streamId })
    }

    /**
     * Storage endpoints of a stream, resolved on chain (the providers that
     * actually hold it) rather than assumed. Kept apart from [Entry] because
     * they are learned EARLIER than the registration: the DM inbox resolves
     * them when it is created, long before the user turns notifications on.
     */
    fun rememberEndpoints(streamId: String, urls: List<String>) {
        if (streamId.isEmpty() || urls.isEmpty()) return
        val map = endpointMap()
        map.put(streamId, JSONArray(urls))
        prefs.edit().putString(endpointsKey(), map.toString()).apply()
    }

    fun endpointsFor(streamId: String): List<String> {
        val arr = endpointMap().optJSONArray(streamId) ?: return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
    }

    fun rememberProviders(streamId: String, urls: List<String>) {
        rememberEndpoints(streamId, urls)
        val map = checkedMap()
        map.put(streamId, System.currentTimeMillis())
        prefs.edit().putString(checkedKey(), map.toString()).apply()
    }

    fun providersCheckedAt(streamId: String): Long = checkedMap().optLong(streamId, 0L)

    private fun endpointMap(): JSONObject = try {
        JSONObject(prefs.getString(endpointsKey(), null) ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    private fun checkedMap(): JSONObject = try {
        JSONObject(prefs.getString(checkedKey(), null) ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    private fun endpointsKey(): String =
        if (scopeAddress.isNullOrEmpty()) ENDPOINTS_KEY else "${ENDPOINTS_KEY}_${scopeAddress!!.lowercase()}"

    private fun checkedKey(): String =
        if (scopeAddress.isNullOrEmpty()) CHECKED_KEY else "${CHECKED_KEY}_${scopeAddress!!.lowercase()}"

    /** Advances the watermark so the same message never notifies twice. */
    fun updateLastSeen(streamId: String, timestamp: Long) {
        val entries = all().map {
            if (it.streamId == streamId && timestamp > it.lastTimestamp) {
                it.copy(lastTimestamp = timestamp)
            } else it
        }
        save(entries)
    }

    private companion object {
        const val KEY = "push_channels"
        const val ENDPOINTS_KEY = "push_endpoints"
        const val CHECKED_KEY = "push_providers_checked"
    }
}
