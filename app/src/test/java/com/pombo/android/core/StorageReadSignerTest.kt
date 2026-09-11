package com.pombo.android.core

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity with the web's storageReadSigner.js, locked by web-generated vectors
 * (Pombo Web tests/vectors/gen_storage_read_vectors.mjs,
 * docs/STORAGE-signed-read-vectors.json): the canonical query, the signed
 * message and the four headers must match byte for byte, or one client reads
 * gated history from a Pombo storage node and the other gets 401.
 */
class StorageReadSignerTest {

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
    private val enc = java.net.URLEncoder.encode(STREAM, "UTF-8")

    @Test
    fun `parse splits a data read url`() {
        val p = StorageReadSigner.parse("https://1.storage.example/streams/$enc/data/partitions/2/last?count=50&format=raw")
        assertNotNull(p)
        assertEquals("https://1.storage.example", p!!.base)
        assertEquals(STREAM, p.streamId)
        assertEquals(2, p.partition)
        assertEquals("last", p.resendType)
        assertEquals("count=50&format=raw", p.canonicalQuery)
    }

    @Test
    fun `parse keeps a path prefix in the base and rejects other urls`() {
        val p = StorageReadSigner.parse("https://host.example/storage/streams/$enc/data/partitions/0/range?fromTimestamp=1&toTimestamp=2")
        assertEquals("https://host.example/storage", p!!.base)
        assertEquals("range", p.resendType)
        assertNull(StorageReadSigner.parse("https://1.storage.example/capabilities"))
        assertNull(StorageReadSigner.parse("https://1.storage.example/streams/$enc/data/partitions/0/purge"))
        assertNull(StorageReadSigner.parse("https://1.storage.example/streams/$enc/metadata"))
        assertNull(StorageReadSigner.parse("not a url"))
    }

    @Test
    fun `canonicalQuery sorts by name, decodes values, keeps repeats in order`() {
        assertEquals("count=5&format=raw", StorageReadSigner.canonicalQuery("format=raw&count=5"))
        assertEquals("format=raw&msgChainId=Ab3/x+z", StorageReadSigner.canonicalQuery("msgChainId=Ab3%2Fx%2Bz&format=raw"))
        assertEquals("count=1&x=b&x=a", StorageReadSigner.canonicalQuery("x=b&count=1&x=a"))
        assertEquals("", StorageReadSigner.canonicalQuery(""))
    }

    @Test
    fun `buildMessage joins the fields one per line`() {
        assertEquals(
            "pombo-storage-node\nread\n$STREAM\n1\n1789000000000\nabc\nfrom\nformat=raw&fromTimestamp=0",
            StorageReadSigner.buildMessage(STREAM, 1, 1789000000000L, "abc", "from", "format=raw&fromTimestamp=0")
        )
    }

    @Test
    fun `randomNonce is 32 lowercase hex chars and unique`() {
        val a = StorageReadSigner.randomNonce()
        assertTrue(a, Regex("^[0-9a-f]{32}$").matches(a))
        assertTrue(a != StorageReadSigner.randomNonce())
    }

    @Test
    fun `headers need a key`() {
        val p = StorageReadSigner.parse("https://1.storage.example/streams/$enc/data/partitions/0/last?count=1")!!
        assertNull(StorageReadSigner.headers(p, null))
        assertNull(StorageReadSigner.headers(p, ""))
    }

    @Test
    fun `every web vector reproduces byte for byte`() {
        val v = vectors("STORAGE-signed-read-vectors.json")
        val priv = v.getString("userPriv")
        val arr = v.getJSONArray("vectors")
        assertTrue(arr.length() > 0)
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            val name = c.getString("name")
            val p = StorageReadSigner.parse(c.getString("url"))
            assertNotNull(name, p)
            assertEquals(name, c.getString("streamId"), p!!.streamId)
            assertEquals(name, c.getInt("partition"), p.partition)
            assertEquals(name, c.getString("resendType"), p.resendType)
            assertEquals(name, c.getString("canonicalQuery"), p.canonicalQuery)
            val message = StorageReadSigner.buildMessage(
                p.streamId, p.partition, c.getLong("issuedAt"), c.getString("nonce"), p.resendType, p.canonicalQuery
            )
            assertEquals(name, c.getString("message"), message)
            val headers = StorageReadSigner.headers(p, priv, c.getLong("issuedAt"), c.getString("nonce"))
            assertNotNull(name, headers)
            val expected = c.getJSONObject("headers")
            for (key in expected.keys()) {
                assertEquals("$name: $key", expected.getString(key), headers!![key])
            }
            assertEquals(name, expected.length(), headers!!.size)
        }
    }
}
