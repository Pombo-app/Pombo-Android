package com.pombo.android.core

import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Storage Endpoint Resolution (on-chain) — native mirror of web
 * `src/js/storageEndpoints.js`.
 *
 * Resolves the HTTP endpoints of the storage nodes assigned to a stream from
 * the on-chain registries (via the bridge's `resolveStorageEndpoints`, which
 * calls `getStorageNodes` + `getStorageNodeMetadata`). Only web-safe URLs
 * (https, hostname, non-localhost, non-IP-literal) survive filtering — the
 * same filter the web applies, kept here (not in the bridge) so it is
 * unit-testable on the JVM.
 *
 * Health tracking is session-scoped:
 *  - consecutive-failure ejection from the rotation ([noteFailure]/[noteSuccess]),
 *  - the features a node URL announces on `GET /capabilities` (Pombo storage
 *    node fork: metadata, storedAt, purge, signedReads); vanilla nodes answer
 *    404 and are remembered as announcing nothing ([probeCapabilities]),
 *  - whether a node URL supports the Pombo `format=metadata` fast path
 *    ([supportsMetaFormat]/[setMetaFormatSupport]): from the capabilities when
 *    announced, else recorded by the engine; vanilla nodes answer HTTP 400.
 *
 * A "provider" is one on-chain node address; its URLs may front a cluster
 * sharing one database, so a feature belongs to the provider when any of its
 * URLs announces it ([probeStream]/[providersWith]).
 *
 * The direct HTTP reads themselves run natively (OkHttp) — there is no reason to
 * shuttle 240 KB chunks through the WebView for plain HTTPS GETs.
 *
 * @param fetcher raw node fetcher (bridge-backed in production, a fake in tests);
 *   returns every node's URLs UNFILTERED — [resolve] applies the web-safe filter.
 * @param capabilityFetcher `GET /capabilities` for one base URL: the announced
 *   features, null on 404 (vanilla), throws on any other failure.
 * @param clock injectable time source, so the TTL is testable without sleeping.
 */
class StorageEndpoints(
    private val fetcher: suspend (streamId: String) -> List<Node>,
    private val ttlMs: Long = StorageMediaConfig.ENDPOINT_CACHE_TTL_MS,
    private val failureLimit: Int = StorageMediaConfig.NODE_FAILURE_LIMIT,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val capabilityFetcher: suspend (url: String) -> Set<String>? = { StorageHttp.fetchCapabilities(it) }
) {
    /** A storage node and its (web-safe once resolved) HTTP base URLs. */
    data class Node(val nodeAddress: String, val urls: List<String>)

    /** A provider with the union of the features its URLs announce. */
    data class Provider(val nodeAddress: String, val urls: List<String>, val features: Set<String>)

    private class Cached(val at: Long, val nodes: List<Node>)
    private class CachedCapabilities(val at: Long, val features: Set<String>)

    private val lock = Mutex()
    private val cache = HashMap<String, Cached>()
    private val inFlight = HashMap<String, CompletableDeferred<List<Node>>>()
    // Read by capabilitiesOf/hasFeature/supportsMetaFormat without the mutex.
    private val capabilities = ConcurrentHashMap<String, CachedCapabilities>()
    private val capabilityProbes = HashMap<String, CompletableDeferred<Set<String>?>>()

    // url -> consecutive failure count / format=metadata support. Touched by
    // noteFailure/noteSuccess/setMetaFormatSupport without the mutex, so kept thread-safe.
    private val failures = ConcurrentHashMap<String, Int>()
    private val metaFormat = ConcurrentHashMap<String, Boolean>()

    /**
     * Resolve the storage nodes (and their web-safe HTTP URLs) for a stream.
     * Cached per stream with a TTL; concurrent callers share one resolution.
     * May return an empty list (no storage, or no node published a web-safe URL).
     */
    suspend fun resolve(streamId: String, force: Boolean = false): List<Node> {
        val deferred: CompletableDeferred<List<Node>>
        var owner = false
        lock.withLock {
            val cached = cache[streamId]
            if (!force && cached != null && clock() - cached.at < ttlMs) return cached.nodes
            val existing = inFlight[streamId]
            if (existing != null) {
                // Dedupe concurrent resolutions even when force is set, exactly
                // like the web (force only bypasses the cache, not the in-flight).
                deferred = existing
            } else {
                deferred = CompletableDeferred()
                inFlight[streamId] = deferred
                owner = true
            }
        }
        if (!owner) return deferred.await()

        try {
            val nodes = fetcher(streamId)
                .map { n ->
                    Node(
                        n.nodeAddress.lowercase(),
                        n.urls.filter { isWebSafeStorageNodeUrl(it) }.map { normalizeUrl(it) }
                    )
                }
                .filter { it.urls.isNotEmpty() }
            lock.withLock {
                cache[streamId] = Cached(clock(), nodes)
                inFlight.remove(streamId)
            }
            deferred.complete(nodes)
            return nodes
        } catch (e: Throwable) {
            lock.withLock { inFlight.remove(streamId) }
            deferred.completeExceptionally(e)
            throw e
        }
    }

    /**
     * Flat rotation list of healthy base URLs for a stream. EVERY healthy URL is
     * a rotation slot — the Pombo cluster registers one node address with two
     * URLs, and taking only the first per node pinned all direct reads to a
     * single server (halved throughput on the web). Slots interleave by URL index
     * so multi-URL nodes and multi-node sets both spread fairly. Ejected URLs
     * ([noteFailure] reached the limit) are skipped. May be empty.
     */
    suspend fun rotation(streamId: String): List<String> {
        val nodes = resolve(streamId)
        val out = ArrayList<String>()
        var i = 0
        while (true) {
            var any = false
            for (node in nodes) {
                val u = node.urls.getOrNull(i) ?: continue
                any = true
                if ((failures[u] ?: 0) < failureLimit) out.add(u)
            }
            if (!any) break
            i++
        }
        return out
    }

    /** Record a failed read against a node URL (ejects after [failureLimit] in a row). */
    fun noteFailure(url: String) {
        val u = normalizeUrl(url)
        failures[u] = (failures[u] ?: 0) + 1
    }

    /** Record a successful read (resets the consecutive-failure count). */
    fun noteSuccess(url: String) {
        failures.remove(normalizeUrl(url))
    }

    /**
     * Features a node URL announces on `GET /capabilities`. Cached per URL with
     * the endpoint TTL; concurrent probes share one request. A 404 is a vanilla
     * node and caches as an empty set; any other failure is not cached, so the
     * next call probes again. Null = the probe failed.
     */
    suspend fun probeCapabilities(url: String, force: Boolean = false): Set<String>? {
        val u = normalizeUrl(url)
        if (u.isEmpty()) return null
        val deferred: CompletableDeferred<Set<String>?>
        var owner = false
        lock.withLock {
            val cached = capabilities[u]
            if (!force && cached != null && clock() - cached.at < ttlMs) return cached.features
            val existing = capabilityProbes[u]
            if (existing != null) {
                deferred = existing
            } else {
                deferred = CompletableDeferred()
                capabilityProbes[u] = deferred
                owner = true
            }
        }
        if (!owner) return deferred.await()

        val features = try {
            capabilityFetcher(u) ?: emptySet()
        } catch (e: Exception) {
            null
        }
        lock.withLock {
            if (features != null) capabilities[u] = CachedCapabilities(clock(), features)
            capabilityProbes.remove(u)
        }
        deferred.complete(features)
        return features
    }

    /** Cached capabilities of a node URL, without probing. Null = not probed (or the probe failed). */
    fun capabilitiesOf(url: String): Set<String>? = capabilities[normalizeUrl(url)]?.features

    /** True only when the URL has been probed and announces the feature. */
    fun hasFeature(url: String, feature: String): Boolean = capabilitiesOf(url)?.contains(feature) == true

    /** Resolve a stream's providers and probe every URL in parallel. */
    suspend fun probeStream(streamId: String): List<Provider> = coroutineScope {
        val nodes = resolve(streamId)
        nodes.flatMap { it.urls }.map { u -> async { probeCapabilities(u) } }.awaitAll()
        nodes.map { n ->
            Provider(n.nodeAddress, n.urls, n.urls.flatMap { capabilitiesOf(it) ?: emptySet() }.toSet())
        }
    }

    /**
     * Providers of a stream that announce a feature, each reduced to the URLs
     * that announce it (a request goes to one of them, the rest are retries).
     */
    suspend fun providersWith(streamId: String, feature: String): List<Node> =
        probeStream(streamId)
            .filter { feature in it.features }
            .map { p -> Node(p.nodeAddress, p.urls.filter { hasFeature(it, feature) }) }

    /**
     * `format=metadata` support for a node URL. An explicit record from a read
     * wins; otherwise the answer comes from the announced capabilities. Null =
     * not known yet.
     */
    fun supportsMetaFormat(url: String): Boolean? {
        val u = normalizeUrl(url)
        metaFormat[u]?.let { return it }
        return if (capabilities[u]?.features?.contains("metadata") == true) true else null
    }

    fun setMetaFormatSupport(url: String, supported: Boolean) {
        metaFormat[normalizeUrl(url)] = supported
    }

    /** Drop the cached node set for a stream (e.g. after add/remove storage node). */
    fun invalidate(streamId: String) {
        // Not on the hot path; a short critical section is fine.
        cache.remove(streamId)
    }

    /** Full reset (logout / client re-init). */
    fun clear() {
        cache.clear()
        inFlight.clear()
        failures.clear()
        metaFormat.clear()
        capabilities.clear()
        capabilityProbes.clear()
    }

    companion object {
        /** Trim whitespace and any trailing slash(es) — the cache/health key form. */
        fun normalizeUrl(url: String): String = url.trim().trimEnd('/')

        private val IPV4 = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")

        /** Mirror of web `isIpLiteralHost`: dotted-quad OR any colon (IPv6). */
        private fun isIpLiteralHost(hostname: String): Boolean {
            if (hostname.isEmpty()) return false
            if (IPV4.matches(hostname)) return true
            return hostname.contains(':')
        }

        /**
         * Mirror of web `isWebSafeStorageNodeUrl`: https scheme, a real hostname
         * (not empty/localhost), and not an IP literal. A malformed URL is unsafe.
         */
        fun isWebSafeStorageNodeUrl(value: String): Boolean {
            return try {
                val uri = URI(value.trim())
                if (uri.scheme?.lowercase() != "https") return false
                // getHost() keeps IPv6 brackets ("[::1]"); the ':' check catches it.
                val host = uri.host?.lowercase() ?: return false
                if (host.isEmpty() || host == "localhost" || host.endsWith(".localhost")) return false
                if (isIpLiteralHost(host)) return false
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}
