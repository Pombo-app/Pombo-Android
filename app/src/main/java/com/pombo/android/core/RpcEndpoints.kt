package com.pombo.android.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Polygon RPC endpoints and the user's selection over them — same keys, labels,
 * order and storage shape as the web's RPC_ENDPOINTS (config.js).
 *
 * What is enabled is exactly what gets used. Nothing is appended underneath.
 * The order only matters where the app walks the list (gate reads, the first
 * two of the access quorum, [GasEstimator]); the Streamr SDK sends each
 * request to one of them at random.
 */
object RpcEndpoints {

    /**
     * [webviewSafe] records whether the endpoint answers CORS from the bridge's
     * https://pombo.local origin. It is a different property from being alive:
     * an endpoint can serve every origin and still be down, or be up and refuse
     * this one. Only the bridge is constrained by it; [GasEstimator] speaks
     * plain JSON-RPC from Kotlin and CORS never enters into it.
     */
    data class Endpoint(
        val key: String,
        val label: String,
        val url: String,
        val webviewSafe: Boolean
    )

    const val CUSTOM_KEY = "custom"

    val ALL = listOf(
        Endpoint("drpc", "dRPC", "https://polygon.drpc.org", true),
        Endpoint("pocket", "Pocket Network", "https://poly.api.pocket.network", true),
        Endpoint("sentio", "Sentio", "https://matic.rpc.sentio.xyz", true),
        Endpoint("blockmachine", "Blockmachine", "https://rpc-polygon.blockmachine.io/", true),
        Endpoint("tenderly-community", "Tenderly (Polygon community)", "https://tenderly.rpc.polygon.community", true),
        Endpoint("tenderly", "Tenderly", "https://polygon.gateway.tenderly.co", true),
        Endpoint("quiknode", "QuickNode (public)", "https://rpc-mainnet.matic.quiknode.pro", true),
        Endpoint("publicnode", "PublicNode", "https://polygon-bor-rpc.publicnode.com", true),
        Endpoint("1rpc", "1RPC (Privacy)", "https://1rpc.io/matic", true)
    )

    /** On by default: providers that answer contract reads reliably without a key
     * and from both client origins. At most four: the SDK gives each request to
     * one of them at random, so a degraded one fails its share of calls, and every
     * client start waits for all of them. */
    val DEFAULT_ENABLED = listOf("drpc", "pocket", "sentio")

    fun byKey(key: String): Endpoint? = ALL.firstOrNull { it.key == key }

    data class Row(val key: String, val on: Boolean)

    data class Selection(val rows: List<Row>, val customUrl: String) {

        /** The URLs this stands for, in order; a custom row with no URL is nothing. */
        val urls: List<String>
            get() = rows.filter { it.on }.mapNotNull { row ->
                if (row.key == CUSTOM_KEY) customUrl.ifBlank { null } else byKey(row.key)?.url
            }

        fun urlFor(key: String): String? =
            if (key == CUSTOM_KEY) customUrl.ifBlank { null } else byKey(key)?.url

        fun labelFor(key: String): String =
            if (key == CUSTOM_KEY) "Custom" else byKey(key)?.label ?: key

        fun withRow(key: String, on: Boolean) =
            copy(rows = rows.map { if (it.key == key) it.copy(on = on) else it })

        fun moved(key: String, delta: Int): Selection {
            val from = rows.indexOfFirst { it.key == key }
            val to = from + delta
            if (from < 0 || to < 0 || to >= rows.size) return this
            val next = rows.toMutableList()
            next.add(to, next.removeAt(from))
            return copy(rows = next)
        }

        /**
         * Whether the bridge would have somewhere to go. A custom URL counts
         * only once a probe from the WebView has actually reached it, since its
         * CORS is unknown until then.
         */
        fun reachesWebView(customUrlProvenFromWebView: Boolean): Boolean =
            rows.any { row ->
                row.on && when (row.key) {
                    CUSTOM_KEY -> customUrlProvenFromWebView && customUrl.isNotBlank()
                    else -> byKey(row.key)?.webviewSafe == true
                }
            }
    }

    /**
     * Fill in the rows the code knows about and drop the ones it does not, so a
     * key removed from [ALL] disappears from a saved selection and one added to
     * it arrives at the end, disabled. Enabling an endpoint has to stay a
     * deliberate act: it decides who the user talks to.
     */
    fun normalize(saved: List<Row>, customUrl: String): Selection {
        val known = ALL.map { it.key }.toSet() + CUSTOM_KEY
        val rows = mutableListOf<Row>()
        val placed = mutableSetOf<String>()
        for (row in saved) {
            if (row.key !in known || !placed.add(row.key)) continue
            rows += row
        }
        ALL.filter { it.key !in placed }.forEach { rows += Row(it.key, false) }

        // The custom endpoint is a row only once one exists: an empty row that
        // can never be ticked is furniture, not a choice.
        val trimmed = customUrl.trim()
        val ordered = if (trimmed.isBlank()) rows.filter { it.key != CUSTOM_KEY }
        else if (CUSTOM_KEY in placed) rows
        else rows + Row(CUSTOM_KEY, true)

        return Selection(
            rows = if (ordered.any { it.on }) ordered
            else ordered.map { it.copy(on = it.key in DEFAULT_ENABLED) },
            customUrl = trimmed
        )
    }

    fun fromJson(json: String?): Selection {
        val obj = try {
            if (json.isNullOrBlank()) null else JSONObject(json)
        } catch (e: Exception) {
            null
        } ?: return normalize(emptyList(), "")

        val array = obj.optJSONArray("rows") ?: JSONArray()
        val rows = (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { Row(it.optString("key"), it.optBoolean("on")) }
        }
        val customUrl = obj.optString("customUrl")
        if (obj.optInt("v", 2) < 3 && rows.filter { it.on }.map { it.key }.toSet() == PREVIOUS_DEFAULT) {
            return normalize(ALL.map { Row(it.key, false) } + Row(CUSTOM_KEY, false), customUrl)
        }
        return normalize(rows, customUrl)
    }

    /**
     * A selection saved before the default changed whose enabled set is exactly
     * this never chose anything: [fromJson] moves it to the current default,
     * keeping a custom URL as an off row. Saving writes v3, so a later choice is
     * never moved.
     */
    private val PREVIOUS_DEFAULT = setOf("drpc", "publicnode", "tenderly")

    fun toJson(selection: Selection): String = JSONObject()
        .put("v", 3)
        .put("rows", JSONArray().apply {
            selection.rows.forEach { put(JSONObject().put("key", it.key).put("on", it.on)) }
        })
        .put("customUrl", selection.customUrl)
        .toString()

    /**
     * Translate the pre-selection setting, which held one preset key plus a
     * custom URL. 'auto' stood for every endpoint and a provider key for that
     * one alone.
     *
     * 'custom' keeps the default providers beside it: the bridge used to
     * append its own underneath every choice, and a custom URL that turns out
     * to refuse the WebView origin would otherwise leave a migrated install with
     * nothing to connect to.
     */
    fun fromLegacy(preset: String?, customUrl: String?): Selection {
        val custom = customUrl?.trim().orEmpty()
        return when {
            preset.isNullOrBlank() -> normalize(emptyList(), custom)
            preset == "auto" -> normalize(ALL.map { Row(it.key, true) }, custom)
            preset == CUSTOM_KEY -> normalize(
                listOf(Row(CUSTOM_KEY, custom.isNotBlank())) +
                    DEFAULT_ENABLED.map { Row(it, true) },
                custom
            )
            else -> normalize(listOf(Row(preset, true)), custom)
        }
    }
}
