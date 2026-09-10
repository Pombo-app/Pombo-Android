package com.pombo.android.core

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageEndpointsTest {

    /** Drivable clock, so the TTL is tested without sleeping. */
    private class Clock(var t: Long = 1_000L) : () -> Long {
        override fun invoke(): Long = t
    }

    private fun node(addr: String, vararg urls: String) =
        StorageEndpoints.Node(addr, urls.toList())

    // ================= isWebSafeStorageNodeUrl =================

    @Test
    fun `https hostname urls are web-safe`() {
        assertTrue(StorageEndpoints.isWebSafeStorageNodeUrl("https://blob-storage-streamr.online"))
        assertTrue(StorageEndpoints.isWebSafeStorageNodeUrl("https://vps2.blob-storage-streamr.online/"))
    }

    @Test
    fun `non-https schemes are rejected`() {
        assertFalse(StorageEndpoints.isWebSafeStorageNodeUrl("http://blob-storage-streamr.online"))
        assertFalse(StorageEndpoints.isWebSafeStorageNodeUrl("ws://blob-storage-streamr.online"))
        assertFalse(StorageEndpoints.isWebSafeStorageNodeUrl("ftp://blob-storage-streamr.online"))
    }

    @Test
    fun `localhost hosts are rejected`() {
        assertFalse(StorageEndpoints.isWebSafeStorageNodeUrl("https://localhost"))
        assertFalse(StorageEndpoints.isWebSafeStorageNodeUrl("https://localhost:8080"))
        assertFalse(StorageEndpoints.isWebSafeStorageNodeUrl("https://foo.localhost"))
    }

    @Test
    fun `ip literal hosts are rejected`() {
        assertFalse(StorageEndpoints.isWebSafeStorageNodeUrl("https://127.0.0.1"))
        assertFalse(StorageEndpoints.isWebSafeStorageNodeUrl("https://10.0.0.5:8080"))
        assertFalse(StorageEndpoints.isWebSafeStorageNodeUrl("https://[::1]"))
        assertFalse(StorageEndpoints.isWebSafeStorageNodeUrl("https://[2001:db8::1]/data"))
    }

    @Test
    fun `malformed and empty urls are rejected`() {
        assertFalse(StorageEndpoints.isWebSafeStorageNodeUrl(""))
        assertFalse(StorageEndpoints.isWebSafeStorageNodeUrl("not a url"))
        assertFalse(StorageEndpoints.isWebSafeStorageNodeUrl("https://"))
    }

    @Test
    fun `normalizeUrl trims whitespace and trailing slashes`() {
        assertEquals("https://a.example.com", StorageEndpoints.normalizeUrl("  https://a.example.com//  "))
        assertEquals("https://a.example.com/data", StorageEndpoints.normalizeUrl("https://a.example.com/data/"))
    }

    // ================= resolve (filter + cache) =================

    @Test
    fun `resolve filters non-web-safe urls, drops empty nodes, lowercases address, normalizes`() = runBlocking {
        val ep = StorageEndpoints(
            fetcher = {
                listOf(
                    node("0xABCDEF", "https://a.example.com/", "http://insecure.example.com"),
                    node("0xNOURLS", "http://only-insecure.example.com", "https://localhost")
                )
            }
        )
        val nodes = ep.resolve("stream-1")
        assertEquals(1, nodes.size)
        assertEquals("0xabcdef", nodes[0].nodeAddress)                 // lowercased
        assertEquals(listOf("https://a.example.com"), nodes[0].urls)   // insecure dropped, slash normalized
    }

    @Test
    fun `resolve caches within the ttl and re-fetches after it expires`() = runBlocking {
        val clock = Clock(1_000L)
        val calls = AtomicInteger(0)
        val ep = StorageEndpoints(
            fetcher = { calls.incrementAndGet(); listOf(node("0xa", "https://a.example.com")) },
            ttlMs = 10_000L,
            clock = clock
        )

        ep.resolve("s")
        ep.resolve("s")
        assertEquals("cached within ttl", 1, calls.get())

        clock.t = 1_000L + 9_999L
        ep.resolve("s")
        assertEquals("still within ttl", 1, calls.get())

        clock.t = 1_000L + 10_001L
        ep.resolve("s")
        assertEquals("re-fetched after ttl", 2, calls.get())
    }

    @Test
    fun `force bypasses the cache`() = runBlocking {
        val calls = AtomicInteger(0)
        val ep = StorageEndpoints(
            fetcher = { calls.incrementAndGet(); listOf(node("0xa", "https://a.example.com")) }
        )
        ep.resolve("s")
        ep.resolve("s", force = true)
        assertEquals(2, calls.get())
    }

    @Test
    fun `invalidate drops the cached set`() = runBlocking {
        val calls = AtomicInteger(0)
        val ep = StorageEndpoints(
            fetcher = { calls.incrementAndGet(); listOf(node("0xa", "https://a.example.com")) }
        )
        ep.resolve("s")
        ep.invalidate("s")
        ep.resolve("s")
        assertEquals(2, calls.get())
    }

    // ================= rotation (interleave + ejection) =================

    @Test
    fun `rotation interleaves urls by index across nodes`() = runBlocking {
        val ep = StorageEndpoints(
            fetcher = {
                listOf(
                    node("0xa", "https://a1.example.com", "https://a2.example.com"),
                    node("0xb", "https://b1.example.com")
                )
            }
        )
        assertEquals(
            listOf("https://a1.example.com", "https://b1.example.com", "https://a2.example.com"),
            ep.rotation("s")
        )
    }

    @Test
    fun `rotation ejects a url after the failure limit and noteSuccess restores it`() = runBlocking {
        val ep = StorageEndpoints(
            fetcher = { listOf(node("0xa", "https://a1.example.com", "https://a2.example.com")) },
            failureLimit = 3
        )
        assertEquals(2, ep.rotation("s").size)

        repeat(2) { ep.noteFailure("https://a1.example.com") }
        assertEquals("still under the limit", 2, ep.rotation("s").size)

        ep.noteFailure("https://a1.example.com")
        assertEquals(listOf("https://a2.example.com"), ep.rotation("s"))

        ep.noteSuccess("https://a1.example.com")
        assertEquals(2, ep.rotation("s").size)
    }

    @Test
    fun `noteFailure and noteSuccess normalize the url`() = runBlocking {
        val ep = StorageEndpoints(
            fetcher = { listOf(node("0xa", "https://a1.example.com")) },
            failureLimit = 1
        )
        // Trailing-slash form must eject the normalized url used by the rotation.
        ep.noteFailure("https://a1.example.com/")
        assertTrue(ep.rotation("s").isEmpty())
    }

    // ================= format=metadata capability =================

    @Test
    fun `meta-format support is unset until recorded, then normalized`() {
        val ep = StorageEndpoints(fetcher = { emptyList() })
        assertNull(ep.supportsMetaFormat("https://a.example.com"))
        ep.setMetaFormatSupport("https://a.example.com/", false)
        assertEquals(false, ep.supportsMetaFormat("https://a.example.com"))
        ep.setMetaFormatSupport("https://a.example.com", true)
        assertEquals(true, ep.supportsMetaFormat("https://a.example.com/"))
    }

    // ================= GET /capabilities =================

    private val FORK = setOf("metadata", "storedAt", "purge", "signedReads")

    @Test
    fun `probeCapabilities caches the announced features per url and answers hasFeature`() = runBlocking {
        val calls = AtomicInteger(0)
        val ep = StorageEndpoints(
            fetcher = { emptyList() },
            capabilityFetcher = { calls.incrementAndGet(); FORK }
        )
        assertEquals(FORK, ep.probeCapabilities("https://a.example.com/"))
        assertTrue(ep.hasFeature("https://a.example.com", "purge"))
        assertFalse(ep.hasFeature("https://a.example.com", "teleport"))
        ep.probeCapabilities("https://a.example.com")
        assertEquals("cached within ttl", 1, calls.get())
    }

    @Test
    fun `a 404 is remembered as announcing nothing and leaves the metadata probe alone`() = runBlocking {
        val calls = AtomicInteger(0)
        val ep = StorageEndpoints(
            fetcher = { emptyList() },
            capabilityFetcher = { calls.incrementAndGet(); null }
        )
        assertEquals(emptySet<String>(), ep.probeCapabilities("https://vanilla.example.com"))
        assertFalse(ep.hasFeature("https://vanilla.example.com", "metadata"))
        // Production nodes carry format=metadata without /capabilities: the
        // engine's own 400 probe still decides.
        assertNull(ep.supportsMetaFormat("https://vanilla.example.com"))
        ep.probeCapabilities("https://vanilla.example.com")
        assertEquals(1, calls.get())
    }

    @Test
    fun `a failed probe is not cached`() = runBlocking {
        val calls = AtomicInteger(0)
        val ep = StorageEndpoints(
            fetcher = { emptyList() },
            capabilityFetcher = {
                if (calls.incrementAndGet() == 1) throw StorageHttp.HttpStatusException(503)
                setOf("purge")
            }
        )
        assertNull(ep.probeCapabilities("https://a.example.com"))
        assertNull(ep.capabilitiesOf("https://a.example.com"))
        assertFalse(ep.hasFeature("https://a.example.com", "purge"))
        assertEquals(setOf("purge"), ep.probeCapabilities("https://a.example.com"))
        assertTrue(ep.hasFeature("https://a.example.com", "purge"))
        assertEquals(2, calls.get())
    }

    @Test
    fun `capabilities expire with the ttl`() = runBlocking {
        val clock = Clock(1_000L)
        val calls = AtomicInteger(0)
        val ep = StorageEndpoints(
            fetcher = { emptyList() },
            ttlMs = 10_000L,
            clock = clock,
            capabilityFetcher = { calls.incrementAndGet(); FORK }
        )
        ep.probeCapabilities("https://a.example.com")
        clock.t = 1_000L + 9_999L
        ep.probeCapabilities("https://a.example.com")
        assertEquals(1, calls.get())
        clock.t = 1_000L + 10_001L
        ep.probeCapabilities("https://a.example.com")
        assertEquals(2, calls.get())
    }

    @Test
    fun `announced metadata support feeds supportsMetaFormat and a recorded 400 wins over it`() = runBlocking {
        val ep = StorageEndpoints(fetcher = { emptyList() }, capabilityFetcher = { FORK })
        ep.probeCapabilities("https://a.example.com")
        assertEquals(true, ep.supportsMetaFormat("https://a.example.com/"))
        ep.setMetaFormatSupport("https://a.example.com", false)
        assertEquals(false, ep.supportsMetaFormat("https://a.example.com"))
    }

    @Test
    fun `probeStream unions features per provider and providersWith keeps only announcing urls`() = runBlocking {
        // Provider A fronts a cluster: one URL upgraded, one still vanilla.
        val calls = AtomicInteger(0)
        val ep = StorageEndpoints(
            fetcher = {
                listOf(
                    node("0xA", "https://a1.example.com", "https://a2.example.com"),
                    node("0xB", "https://b.example.com")
                )
            },
            capabilityFetcher = { url -> calls.incrementAndGet(); if (url == "https://a1.example.com") FORK else null }
        )
        val providers = ep.probeStream("s")
        assertEquals(2, providers.size)
        assertEquals(FORK, providers[0].features)
        assertEquals(emptySet<String>(), providers[1].features)
        assertEquals(3, calls.get())

        assertEquals(
            listOf(StorageEndpoints.Node("0xa", listOf("https://a1.example.com"))),
            ep.providersWith("s", "purge")
        )
        assertTrue(ep.providersWith("s", "teleport").isEmpty())
        assertEquals("second pass served from the cache", 3, calls.get())
    }

    @Test
    fun `probeStream on a stream without storage probes nothing`() = runBlocking {
        val calls = AtomicInteger(0)
        val ep = StorageEndpoints(fetcher = { emptyList() }, capabilityFetcher = { calls.incrementAndGet(); FORK })
        assertTrue(ep.probeStream("s").isEmpty())
        assertEquals(0, calls.get())
    }

    @Test
    fun `clear forgets probed capabilities`() = runBlocking {
        val ep = StorageEndpoints(fetcher = { emptyList() }, capabilityFetcher = { FORK })
        ep.probeCapabilities("https://a.example.com")
        ep.clear()
        assertNull(ep.capabilitiesOf("https://a.example.com"))
    }
}
