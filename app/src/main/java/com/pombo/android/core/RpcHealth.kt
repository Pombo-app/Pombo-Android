package com.pombo.android.core

import org.json.JSONObject

/**
 * What the bridge's health probe found for each chosen RPC endpoint
 * (pombo_bridge.html, api.rpcHealthProbe), and when the bridge has to be
 * reloaded to act on it. The same rules as the web's rpcHealth.js.
 */
object RpcHealth {

    const val MIN_REBUILD_INTERVAL_MS = 10 * 60 * 1000L
    private const val PROBE_TIMEOUT_S = 3

    data class Verdict(
        val ok: Boolean,
        val reason: String? = null,
        val status: Int? = null,
        val code: Int? = null,
        val behind: Int? = null,
        val at: Long = 0L
    )

    /**
     * One report: the urls the running client was built with, the ones that
     * pass now (never empty, [fallback] when that is all of them), and the
     * last probe's verdicts.
     */
    data class Report(
        val applied: List<String>,
        val usable: List<String>,
        val verdicts: Map<String, Verdict>,
        val fallback: Boolean
    )

    fun parse(json: String): Report? = try {
        val o = JSONObject(json)
        val list = { key: String -> o.optJSONArray(key)?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList() }
        val applied = list("applied")
        val verdicts = HashMap<String, Verdict>()
        o.optJSONObject("verdicts")?.let { v ->
            v.keys().forEach { url ->
                val e = v.getJSONObject(url)
                verdicts[url] = Verdict(
                    ok = e.optBoolean("ok", true),
                    reason = e.optString("reason").ifEmpty { null },
                    status = if (e.has("status")) e.optInt("status") else null,
                    code = if (e.has("code")) e.optInt("code") else null,
                    behind = if (e.has("behind")) e.optInt("behind") else null,
                    at = e.optLong("at")
                )
            }
        }
        Report(applied, list("usable").ifEmpty { applied }, verdicts, o.optBoolean("fallback", false))
    } catch (e: Exception) {
        null
    }

    fun shouldRebuild(
        applied: List<String>,
        usable: List<String>,
        lastRebuildAt: Long,
        now: Long,
        writing: Boolean
    ): Boolean {
        if (applied.toSet() == usable.toSet()) return false
        if (writing) return false
        return now - lastRebuildAt >= MIN_REBUILD_INTERVAL_MS
    }

    fun describe(v: Verdict): String = when (v.reason) {
        "overloaded" -> "overloaded (HTTP ${v.status})"
        "limited" -> "rate limited"
        "http" -> "HTTP ${v.status}"
        "refused" -> "refused the call (${v.code})"
        "timeout" -> "no answer in $PROBE_TIMEOUT_S s"
        "unreachable" -> "unreachable (network or CORS)"
        "lagging" -> "${v.behind} blocks behind"
        else -> "wrong answer to a contract read"
    }
}
