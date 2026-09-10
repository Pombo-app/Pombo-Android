package com.pombo.android.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Purge — removing specific messages from Pombo storage nodes. Native mirror
 * of the web's `storagePurge.js`; parity of the signed message is locked by
 * docs/STORAGE-purge-vectors.json.
 *
 * `POST /streams/{id}/data/partitions/{p}/purge` with
 * `{ user, issuedAt, nonce, signature, targets: [{ timestamp, sequenceNumber }] }`,
 * signed (EIP-191 personal_sign) over one field per line:
 * `pombo-storage-node / purge / streamId / partition / issuedAt / nonce /`
 * then `timestamp:sequenceNumber` per target in the order sent. The node
 * answers per target with `deleted`, `forbidden` or `not_found`.
 *
 * A purge is per PROVIDER (one on-chain storage node address, possibly a
 * cluster behind several URLs sharing one database): one request to one of
 * its URLs, the others being retries, fanned out over every provider that
 * announces `purge`. It never replaces the client-side hide.
 */
object StoragePurge {

    const val MAX_TARGETS = 100
    const val LOOKUP_WINDOW_MS = 10_000L
    const val LOOKUP_TIE_MS = 1_000L

    data class Target(val timestamp: Long, val sequenceNumber: Int)

    data class ProviderOutcome(
        val provider: String,
        val url: String?,
        val status: Int,
        /** target → node verdict (`deleted`, `forbidden`, `not_found`). */
        val results: Map<Target, String>,
        val error: String? = null
    )

    data class Outcome(
        val providers: Int,
        /** Providers where every target is now gone (deleted or never held). */
        val erasedOn: Int,
        /** Providers that refused at least one target. */
        val forbiddenOn: Int,
        val unreachable: Int,
        val outcomes: List<ProviderOutcome>,
        /** Why no request went out at all (the target could not be resolved). */
        val error: String? = null
    )

    /** The exact string the client signs. */
    fun buildMessage(streamId: String, partition: Int, issuedAt: Long, nonce: String, targets: List<Target>): String =
        (listOf("pombo-storage-node", "purge", streamId, partition.toString(), issuedAt.toString(), nonce) +
            targets.map { "${it.timestamp}:${it.sequenceNumber}" }).joinToString("\n")

    /** The signed request body. Throws on an empty or oversized target list, or without a key. */
    fun signedBody(
        streamId: String, partition: Int, targets: List<Target>, privateKeyHex: String,
        issuedAt: Long = System.currentTimeMillis(), nonce: String = StorageReadSigner.randomNonce()
    ): JSONObject {
        require(targets.isNotEmpty()) { "No purge targets" }
        require(targets.size <= MAX_TARGETS) { "At most $MAX_TARGETS targets per purge" }
        require(privateKeyHex.isNotEmpty()) { "No key to sign the purge with" }
        val message = buildMessage(streamId, partition, issuedAt, nonce, targets)
        val signature = SigningOracle.signMessage(message.toByteArray(Charsets.UTF_8), privateKeyHex)
        require(signature.isNotEmpty()) { "Signing failed" }
        val arr = JSONArray()
        for (t in targets) arr.put(JSONObject().put("timestamp", t.timestamp).put("sequenceNumber", t.sequenceNumber))
        return JSONObject()
            .put("user", EthereumSigner.checksumAddress(EthereumSigner.address(privateKeyHex)))
            .put("issuedAt", issuedAt)
            .put("nonce", nonce)
            .put("signature", signature)
            .put("targets", arr)
    }

    fun purgeUrl(base: String, streamId: String, partition: Int): String =
        "${base.trimEnd('/')}/streams/${java.net.URLEncoder.encode(streamId, "UTF-8")}/data/partitions/$partition/purge"

    /**
     * Purge on one provider: the first URL that answers decides; one that
     * cannot be reached is skipped for the next. Every attempt signs afresh,
     * because the node accepts a nonce once.
     * @param post `(url, jsonBody) -> (status, responseBody)`; throws when unreachable.
     */
    suspend fun purgeOnProvider(
        provider: StorageEndpoints.Node, streamId: String, partition: Int, targets: List<Target>,
        privateKeyHex: String,
        post: suspend (url: String, body: String) -> Pair<Int, String> = StorageHttp::postJson
    ): ProviderOutcome {
        var lastError: String? = null
        for (url in provider.urls) {
            try {
                val body = signedBody(streamId, partition, targets, privateKeyHex)
                val (status, text) = post(purgeUrl(url, streamId, partition), body.toString())
                if (status / 100 != 2) return ProviderOutcome(provider.nodeAddress, url, status, emptyMap())
                return ProviderOutcome(provider.nodeAddress, url, status, parseResults(text))
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        return ProviderOutcome(provider.nodeAddress, null, 0, emptyMap(), lastError ?: "unreachable")
    }

    /** `{results:[{timestamp,sequenceNumber,result}]}` → target → verdict. */
    fun parseResults(text: String): Map<Target, String> {
        val arr = runCatching { JSONObject(text).optJSONArray("results") }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<Target, String>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            out[Target(r.optLong("timestamp"), r.optInt("sequenceNumber"))] = r.optString("result")
        }
        return out
    }

    /**
     * The row a lookup by time addresses: the closest to [anchor]. Refuses to
     * guess when two rows are about as close, e.g. two messages at one instant.
     */
    fun closestTarget(rows: List<StorageHttp.MetaRow>, anchor: Long): Target {
        val candidates = rows.filter { it.timestamp > 0L }.sortedBy { kotlin.math.abs(it.timestamp - anchor) }
        check(candidates.isNotEmpty()) { "The message is not on storage" }
        check(
            candidates.size < 2 ||
                kotlin.math.abs(candidates[1].timestamp - anchor) - kotlin.math.abs(candidates[0].timestamp - anchor) >= LOOKUP_TIE_MS
        ) { "Several messages share this instant; cannot tell which to erase" }
        return Target(candidates[0].timestamp, candidates[0].sequenceNumber)
    }

    /** Fan the purge out to every provider of the stream that announces it. */
    suspend fun purgeMessages(
        endpoints: StorageEndpoints, streamId: String, partition: Int, targets: List<Target>,
        privateKeyHex: String,
        post: suspend (url: String, body: String) -> Pair<Int, String> = StorageHttp::postJson
    ): Outcome {
        val providers = endpoints.providersWith(streamId, "purge")
        val outcomes = providers.map { purgeOnProvider(it, streamId, partition, targets, privateKeyHex, post) }
        val gone = { o: ProviderOutcome ->
            o.status / 100 == 2 && targets.all { t -> o.results[t] == "deleted" || o.results[t] == "not_found" }
        }
        val forbidden = { o: ProviderOutcome -> o.status / 100 == 2 && o.results.values.any { it == "forbidden" } }
        return Outcome(
            providers = providers.size,
            erasedOn = outcomes.count(gone),
            forbiddenOn = outcomes.count(forbidden),
            unreachable = outcomes.count { it.status == 0 },
            outcomes = outcomes
        )
    }
}
