package com.pombo.android.core

import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity with the web's storagePurge.js, locked by web-generated vectors
 * (Pombo Web tests/vectors/gen_storage_purge_vectors.mjs,
 * docs/STORAGE-purge-vectors.json), plus the fan-out rules: one request per
 * provider, the next URL only when one is unreachable, a fresh nonce every
 * attempt, and the per-provider verdict.
 */
class StoragePurgeTest {

    private fun vectors(name: String): JSONObject {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "docs/$name")
            if (candidate.isFile) return JSONObject(candidate.readText())
            dir = dir.parentFile
        }
        throw AssertionError("parity vectors not found: docs/$name")
    }

    private val STREAM = "0xaaaabbbbccccddddeeeeffff0000111122223333/deadbeef01-1"
    private val KEY = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d"
    private val target = StoragePurge.Target(100L, 0)

    @Test
    fun `buildMessage joins the fields with one target line each`() {
        assertEquals(
            "pombo-storage-node\npurge\n$STREAM\n2\n1789000000000\nabc\n10:0\n11:3",
            StoragePurge.buildMessage(STREAM, 2, 1789000000000L, "abc", listOf(StoragePurge.Target(10, 0), StoragePurge.Target(11, 3)))
        )
    }

    @Test
    fun `signedBody refuses empty and oversized lists and a missing key`() {
        assertThrows(IllegalArgumentException::class.java) { StoragePurge.signedBody(STREAM, 0, emptyList(), KEY) }
        val many = (0..StoragePurge.MAX_TARGETS).map { StoragePurge.Target(it.toLong(), 0) }
        assertThrows(IllegalArgumentException::class.java) { StoragePurge.signedBody(STREAM, 0, many, KEY) }
        assertThrows(IllegalArgumentException::class.java) { StoragePurge.signedBody(STREAM, 0, listOf(target), "") }
    }

    @Test
    fun `every web vector reproduces byte for byte`() {
        val v = vectors("STORAGE-purge-vectors.json")
        val priv = v.getString("userPriv")
        val arr = v.getJSONArray("vectors")
        assertTrue(arr.length() > 0)
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            val name = c.getString("name")
            val expected = c.getJSONObject("body")
            val targetsArr = expected.getJSONArray("targets")
            val targets = (0 until targetsArr.length()).map {
                val t = targetsArr.getJSONObject(it)
                StoragePurge.Target(t.getLong("timestamp"), t.getInt("sequenceNumber"))
            }
            val message = StoragePurge.buildMessage(c.getString("streamId"), c.getInt("partition"), expected.getLong("issuedAt"), expected.getString("nonce"), targets)
            assertEquals(name, c.getString("message"), message)
            val body = StoragePurge.signedBody(c.getString("streamId"), c.getInt("partition"), targets, priv, expected.getLong("issuedAt"), expected.getString("nonce"))
            for (key in listOf("user", "issuedAt", "nonce", "signature")) {
                assertEquals("$name: $key", expected.get(key).toString(), body.get(key).toString())
            }
            assertEquals(name, expected.getJSONArray("targets").toString(), body.getJSONArray("targets").toString())
        }
    }

    @Test
    fun `purgeOnProvider posts to the first url and returns the verdicts`() = runBlocking {
        val provider = StorageEndpoints.Node("0xprov", listOf("https://a1.example", "https://a2.example"))
        val calls = ArrayList<Pair<String, String>>()
        val out = StoragePurge.purgeOnProvider(provider, STREAM, 0, listOf(target), KEY) { url, body ->
            calls.add(url to body)
            200 to """{"results":[{"timestamp":100,"sequenceNumber":0,"result":"deleted"}]}"""
        }
        assertEquals(1, calls.size)
        assertEquals(StoragePurge.purgeUrl("https://a1.example", STREAM, 0), calls[0].first)
        val body = JSONObject(calls[0].second)
        assertEquals("0x70997970C51812dc3A010C7d01b50e0d17dc79C8", body.getString("user"))
        assertEquals("https://a1.example", out.url)
        assertEquals("deleted", out.results[target])
    }

    @Test
    fun `an unreachable url is skipped for the next, with a new nonce`() = runBlocking {
        val provider = StorageEndpoints.Node("0xprov", listOf("https://a1.example", "https://a2.example"))
        val nonces = ArrayList<String>()
        val out = StoragePurge.purgeOnProvider(provider, STREAM, 0, listOf(target), KEY) { url, body ->
            nonces.add(JSONObject(body).getString("nonce"))
            if (url.startsWith("https://a1")) throw java.io.IOException("ECONNRESET")
            200 to """{"results":[{"timestamp":100,"sequenceNumber":0,"result":"not_found"}]}"""
        }
        assertEquals("https://a2.example", out.url)
        assertEquals(2, nonces.size)
        assertTrue(nonces[0] != nonces[1])
    }

    @Test
    fun `an http refusal stops at that url, all unreachable reports unreachable`() = runBlocking {
        val provider = StorageEndpoints.Node("0xprov", listOf("https://a1.example", "https://a2.example"))
        var calls = 0
        val refused = StoragePurge.purgeOnProvider(provider, STREAM, 0, listOf(target), KEY) { _, _ -> calls++; 403 to "" }
        assertEquals(403, refused.status)
        assertEquals(1, calls)
        val dead = StoragePurge.purgeOnProvider(provider, STREAM, 0, listOf(target), KEY) { _, _ -> throw java.io.IOException("down") }
        assertEquals(0, dead.status)
        assertNull(dead.url)
        assertEquals("down", dead.error)
    }

    @Test
    fun `purgeMessages fans out to every provider announcing purge and counts the verdicts`() = runBlocking {
        val endpoints = StorageEndpoints(
            fetcher = {
                listOf(
                    StorageEndpoints.Node("0xa", listOf("https://a.example")),
                    StorageEndpoints.Node("0xb", listOf("https://b.example")),
                    StorageEndpoints.Node("0xc", listOf("https://c.example")),
                    StorageEndpoints.Node("0xd", listOf("https://d.example"))
                )
            },
            capabilityFetcher = { url -> if (url.startsWith("https://d")) emptySet() else setOf("purge") }
        )
        val out = StoragePurge.purgeMessages(endpoints, STREAM, 0, listOf(target), KEY) { url, _ ->
            when {
                url.startsWith("https://a") -> 200 to """{"results":[{"timestamp":100,"sequenceNumber":0,"result":"deleted"}]}"""
                url.startsWith("https://b") -> 200 to """{"results":[{"timestamp":100,"sequenceNumber":0,"result":"forbidden"}]}"""
                else -> throw java.io.IOException("down")
            }
        }
        assertEquals(3, out.providers)
        assertEquals(1, out.erasedOn)
        assertEquals(1, out.forbiddenOn)
        assertEquals(1, out.unreachable)
    }

    @Test
    fun `parseResults tolerates a malformed body`() {
        assertTrue(StoragePurge.parseResults("not json").isEmpty())
        assertTrue(StoragePurge.parseResults("{}").isEmpty())
    }

    @Test
    fun `closestTarget keeps the row nearest the anchor and refuses to guess between two`() {
        val row = { ts: Long, seq: Int -> StorageHttp.MetaRow(ts, null, seq) }
        assertEquals(StoragePurge.Target(503, 0), StoragePurge.closestTarget(listOf(row(9000, 0), row(503, 0)), 500))
        assertEquals(StoragePurge.Target(500, 4), StoragePurge.closestTarget(listOf(row(500, 4)), 500))
        assertThrows(IllegalStateException::class.java) { StoragePurge.closestTarget(emptyList(), 500) }
        assertThrows(IllegalStateException::class.java) { StoragePurge.closestTarget(listOf(row(500, 0), row(500, 1)), 500) }
    }
}
