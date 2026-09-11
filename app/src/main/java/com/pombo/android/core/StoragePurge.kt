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
    const val CHUNK_WINDOW_PAD_MS = 60_000L

    data class Target(val timestamp: Long, val sequenceNumber: Int)

    /** Rows of one partition addressed together; rows written under another key carry it. */
    data class Group(val partition: Int, val targets: List<Target>, val privateKeyHex: String? = null)

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
        /** Rows addressed, over every partition. */
        val targets: Int = 0,
        /** Why no request went out at all (the target could not be resolved). */
        val error: String? = null
    )

    /** The exact string the client signs; a `stored` query signs the same lines with `stored` as the action. */
    fun buildMessage(streamId: String, partition: Int, issuedAt: Long, nonce: String, targets: List<Target>, action: String = "purge"): String =
        (listOf("pombo-storage-node", action, streamId, partition.toString(), issuedAt.toString(), nonce) +
            targets.map { "${it.timestamp}:${it.sequenceNumber}" }).joinToString("\n")

    /** The signed request body. Throws on an empty or oversized target list, or without a key. */
    fun signedBody(
        streamId: String, partition: Int, targets: List<Target>, privateKeyHex: String,
        issuedAt: Long = System.currentTimeMillis(), nonce: String = StorageReadSigner.randomNonce(),
        action: String = "purge"
    ): JSONObject {
        require(targets.isNotEmpty()) { "No $action targets" }
        require(targets.size <= MAX_TARGETS) { "At most $MAX_TARGETS targets per $action" }
        require(privateKeyHex.isNotEmpty()) { "No key to sign the $action with" }
        val message = buildMessage(streamId, partition, issuedAt, nonce, targets, action)
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

    fun purgeUrl(base: String, streamId: String, partition: Int): String = targetsUrl(base, streamId, partition, "purge")

    fun targetsUrl(base: String, streamId: String, partition: Int, action: String): String =
        "${base.trimEnd('/')}/streams/${java.net.URLEncoder.encode(streamId, "UTF-8")}/data/partitions/$partition/$action"

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
    ): ProviderOutcome = postTargets("purge", provider, streamId, partition, targets, privateKeyHex, post)

    private suspend fun postTargets(
        action: String, provider: StorageEndpoints.Node, streamId: String, partition: Int, targets: List<Target>,
        privateKeyHex: String, post: suspend (url: String, body: String) -> Pair<Int, String>
    ): ProviderOutcome {
        var lastError: String? = null
        for (url in provider.urls) {
            try {
                val body = signedBody(streamId, partition, targets, privateKeyHex, action = action)
                val (status, text) = post(targetsUrl(url, streamId, partition, action), body.toString())
                if (status / 100 != 2) return ProviderOutcome(provider.nodeAddress, url, status, emptyMap())
                return ProviderOutcome(provider.nodeAddress, url, status, parseResults(text))
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        return ProviderOutcome(provider.nodeAddress, null, 0, emptyMap(), lastError ?: "unreachable")
    }

    /**
     * Which of the targets the providers hold, asked of every provider that
     * announces `stored` and signed by whoever may read the stream or wrote
     * the rows. A row counts as present when any provider says so; null when
     * no provider answered.
     */
    suspend fun storedOn(
        providers: List<StorageEndpoints.Node>, streamId: String, partition: Int, targets: List<Target>,
        privateKeyHex: String,
        post: suspend (url: String, body: String) -> Pair<Int, String> = StorageHttp::postJson
    ): Set<Target>? {
        val present = HashSet<Target>()
        var answered = false
        for (p in providers) {
            for (batch in targets.chunked(MAX_TARGETS)) {
                val o = postTargets("stored", p, streamId, partition, batch, privateKeyHex, post)
                if (o.status == 0) break
                if (o.status / 100 != 2) continue
                answered = true
                o.results.forEach { (t, verdict) -> if (verdict == "present") present.add(t) }
            }
        }
        return if (answered) present else null
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

    /**
     * Fan the purge out to every provider of the stream that announces it:
     * one request per partition and per batch of [MAX_TARGETS] targets. A
     * provider counts as erased when every target is gone (deleted or never
     * held); one unreachable batch stops the rest for that provider.
     */
    suspend fun purgeGroups(
        endpoints: StorageEndpoints, streamId: String, groups: List<Group>,
        privateKeyHex: String,
        post: suspend (url: String, body: String) -> Pair<Int, String> = StorageHttp::postJson
    ): Outcome {
        val providers = endpoints.providersWith(streamId, "purge")
        val batches = groups.flatMap { g -> g.targets.chunked(MAX_TARGETS).map { Group(g.partition, it, g.privateKeyHex) } }
        val all = providers.map { p ->
            val outs = ArrayList<Pair<Group, ProviderOutcome>>()
            for (b in batches) {
                val o = purgeOnProvider(p, streamId, b.partition, b.targets, b.privateKeyHex ?: privateKeyHex, post)
                outs.add(b to o)
                if (o.status == 0) break
            }
            outs
        }
        val gone = { (b, o): Pair<Group, ProviderOutcome> ->
            o.status / 100 == 2 && b.targets.all { t -> o.results[t] == "deleted" || o.results[t] == "not_found" }
        }
        return Outcome(
            providers = providers.size,
            erasedOn = all.count { outs -> outs.size == batches.size && outs.all(gone) },
            forbiddenOn = all.count { outs -> outs.any { (_, o) -> o.status / 100 == 2 && o.results.values.any { it == "forbidden" } } },
            unreachable = all.count { outs -> outs.any { (_, o) -> o.status == 0 } },
            outcomes = all.flatten().map { it.second },
            targets = batches.sumOf { it.targets.size }
        )
    }

    /** One partition, one target list. */
    suspend fun purgeMessages(
        endpoints: StorageEndpoints, streamId: String, partition: Int, targets: List<Target>,
        privateKeyHex: String,
        post: suspend (url: String, body: String) -> Pair<Int, String> = StorageHttp::postJson
    ): Outcome = purgeGroups(endpoints, streamId, listOf(Group(partition, targets)), privateKeyHex, post)

    /**
     * The transfer a stored chunk belongs to, read off its header
     * (`[4B metaLen][meta JSON]…`, the StorageMedia chunk payload) without
     * touching the data behind it. Null for anything that is not a v2 chunk.
     */
    fun chunkTransferId(bytes: ByteArray?): String? {
        if (bytes == null || bytes.size < 8) return null
        val metaLen = ((bytes[0].toInt() and 0xff) shl 24) or ((bytes[1].toInt() and 0xff) shl 16) or
            ((bytes[2].toInt() and 0xff) shl 8) or (bytes[3].toInt() and 0xff)
        if (metaLen <= 0 || bytes.size < 4 + metaLen) return null
        return runCatching {
            val meta = JSONObject(String(bytes, 4, metaLen, Charsets.UTF_8))
            if (meta.optString("type") == "binary_file_chunked" && meta.optInt("version") == 2)
                meta.optString("transferId").ifEmpty { null } else null
        }.getOrNull()
    }

    /**
     * The chunk rows of a storage-shared file: every row on the announce's
     * chunk partitions, over the window it declares, whose header names its
     * transfer. Exact by content, at the price of reading the file once more.
     * Rows are sealed the way the channel seals media (epoch key, password
     * key), so [open] is the same opener a download uses; a row that does not
     * open is not this file's.
     * @param read `(url, partition, from, to, onRow)`; throws when the read fails.
     */
    suspend fun fileChunkGroups(
        endpoints: StorageEndpoints, streamId: String, meta: StorageMedia.StorageFileMetadata,
        open: (ByteArray, Long) -> ByteArray = { bytes, _ -> bytes },
        read: suspend (url: String, partition: Int, from: Long, to: Long, onRow: (StorageHttp.Row) -> Unit) -> Unit =
            { url, partition, from, to, onRow -> StorageHttp.directFetchRange(url, streamId, partition, from, to, onRow) }
    ): List<Group> {
        val firstTs = meta.firstChunkTs
        val lastTs = meta.lastChunkTs
        check(meta.transferId.isNotEmpty() && firstTs != null && lastTs != null) { "The file announce does not say where its chunks are" }
        val urls = endpoints.providersWith(streamId, "purge").flatMap { it.urls }
        check(urls.isNotEmpty()) { "No storage provider can identify the chunks" }
        val groups = ArrayList<Group>()
        for (k in 0 until meta.chunkPartitions) {
            val partition = meta.firstChunkPartition + k
            var targets: List<Target>? = null
            for (url in urls) {
                val found = ArrayList<Target>()
                val ok = runCatching {
                    read(url, partition, firstTs - CHUNK_WINDOW_PAD_MS, lastTs + CHUNK_WINDOW_PAD_MS) { r ->
                        val plain = r.content?.let { sealed -> runCatching { open(sealed, r.timestamp) }.getOrNull() }
                        if (chunkTransferId(plain) == meta.transferId) found.add(Target(r.timestamp, r.sequenceNumber))
                    }
                }.isSuccess
                if (ok) { targets = found; break }
            }
            val rows = targets ?: throw IllegalStateException("Could not read the chunks on partition $partition")
            if (rows.isNotEmpty()) groups.add(Group(partition, rows))
        }
        return groups
    }
}
