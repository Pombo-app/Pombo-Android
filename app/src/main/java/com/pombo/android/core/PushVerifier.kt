package com.pombo.android.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Confirms a wake signal actually corresponds to a new message before anything
 * is shown — the same job `verifyChannel` does in sw.js.
 *
 * A push only carries a 1-byte tag, so a matching tag is not evidence: many
 * channels share it. The only way to know is to ask the storage node for the
 * stream's last message and compare against the watermark we already notified
 * about. Anything not newer is a false positive and is dropped silently.
 *
 * A Pombo storage node serves a gated channel's streams and a DM inbox only to
 * a signed read, so this read carries the same `x-pombo-*` headers the app's
 * own history reads do ([StorageReadSigner]); public and password channels are
 * read anonymously, as the node serves them.
 */
class PushVerifier(
    private val endpointsFor: (streamId: String) -> List<String>,
    private val signHeaders: (url: String) -> Map<String, String>?,
    private val http: (url: String, headers: Map<String, String>) -> Response = ::fetch
) {

    /** A node's answer: [body] is null unless the request succeeded. */
    data class Response(val code: Int, val body: String?)

    data class Result(
        val hasNew: Boolean,
        val timestamp: Long = 0L,
        val content: JSONObject? = null,
        val publisherId: String? = null
    )

    suspend fun verify(entry: PushRegistry.Entry): Result = withContext(Dispatchers.IO) {
        val endpoints = endpointsFor(entry.streamId).ifEmpty { LEGACY_ENDPOINTS }
        val signed = needsSignature(entry.type)
        for (endpoint in endpoints) {
            val url = endpoint.trimEnd('/') + API_PATH.format(
                URLEncoder.encode(entry.streamId, "UTF-8"), 0
            )
            val headers = if (signed) {
                signHeaders(url) ?: run {
                    // No key to sign with (locked before the first unlock):
                    // every endpoint would refuse, so do not ask them.
                    android.util.Log.w(TAG, "no signature available for ${entry.streamId.takeLast(24)}")
                    return@withContext Result(hasNew = false)
                }
            } else emptyMap()

            val response = try {
                http(url, headers)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "read failed at $endpoint: ${e.message}")
                continue
            }
            // 4xx is the node answering ABOUT the request (unsigned, no access,
            // stream gone), which the next node would answer the same way. Only
            // a node that failed as a node is worth asking again.
            if (response.code in 400..499) {
                android.util.Log.w(TAG, "HTTP ${response.code} for ${entry.streamId.takeLast(24)}")
                return@withContext Result(hasNew = false)
            }
            if (response.code != 200) {
                android.util.Log.w(TAG, "HTTP ${response.code} at $endpoint")
                continue
            }
            val last = lastMessage(response.body ?: continue) ?: continue

            val timestamp = last.optLong("timestamp", 0L)
            val hasNew = timestamp > entry.lastTimestamp
            return@withContext Result(
                hasNew = hasNew,
                timestamp = timestamp,
                content = if (hasNew) last.optJSONObject("content") else null,
                publisherId = if (hasNew) last.optStringOrNull("publisherId") else null
            )
        }
        // Every endpoint failed: stay silent rather than guess. Showing a
        // notification here would mean showing one for every colliding tag.
        Result(hasNew = false)
    }

    companion object {
        private const val TAG = "PushVerifier"

        /**
         * Used only until a registration has its endpoints resolved on chain:
         * an install that is upgraded mid-flight keeps notifying while the
         * next registration refresh fills them in.
         */
        private val LEGACY_ENDPOINTS = listOf(
            "https://blob-storage-streamr.online",
            "https://vps2.blob-storage-streamr.online"
        )

        private const val API_PATH = "/streams/%s/data/partitions/%d/last?count=1"
        private const val TIMEOUT_MS = 5_000

        /**
         * Streams a Pombo node refuses to serve unsigned: a gated channel's
         * own streams, and the inbox whose SUBSCRIBE belongs to its owner
         * alone. Mirrors the rule in ChannelManager's `readHeaders`.
         */
        fun needsSignature(type: String): Boolean = type == "gated" || type == "dm-inbox"

        internal fun fetch(url: String, headers: Map<String, String>): Response {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            return try {
                val code = conn.responseCode
                Response(code, if (code == 200) conn.inputStream.bufferedReader().use { it.readText() } else null)
            } finally {
                conn.disconnect()
            }
        }

        /**
         * The endpoint answers either with an array or a single object depending on
         * the node build, so both shapes are accepted.
         */
        private fun lastMessage(body: String): JSONObject? {
            val trimmed = body.trim()
            return try {
                if (trimmed.startsWith("[")) {
                    val arr = JSONArray(trimmed)
                    if (arr.length() == 0) null else arr.optJSONObject(arr.length() - 1)
                } else {
                    JSONObject(trimmed)
                }
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Notification body. A channel's own encryption is not undone here, so
         * a closed channel says only that something arrived; a direct message
         * arrives already opened (the sender is read from the same envelope)
         * and shows its text, like the app does when it is running.
         */
        fun preview(type: String, content: JSONObject?): String {
            if (type == "private" || type == "gated") return "New message"
            val direct = type == "dm" || type == "native" || type == "dm-inbox"
            // A sealed envelope that never opened: not ours, or no key yet.
            val sealed = content != null && content.optInt("v") == 2 && content.has("epk")
            if (content == null || sealed) return if (direct) "You have a new message" else "New message"
            return when (content.optString("type")) {
                "text" -> {
                    val text = content.optString("text")
                    if (text.length > 100) text.take(100) + "..."
                    else text.ifEmpty { if (direct) "You have a new message" else "New message" }
                }
                "image" -> "📷 Image"
                // sw.js:318-336 covers the remaining content types too.
                "file" -> "📎 " + content.optString("fileName").ifEmpty { "File" }
                "audio" -> "🎵 Audio message"
                "video", "video_announce" -> "🎬 Video"
                "gif" -> "🎞️ GIF"
                "reaction" -> "Reacted with " + content.optString("emoji").ifEmpty { "👍" }
                else -> "New message"
            }
        }

        private fun JSONObject.optStringOrNull(key: String): String? {
            if (isNull(key)) return null
            return optString(key, "").ifEmpty { null }
        }
    }
}
