package com.pombo.android.core

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Framing for a snapshot too big for one wire message, locked by
 * web-generated vectors (Pombo Web tests/vectors/gen_sync_chunk_vectors.mjs,
 * docs/SYNC-chunk-vectors.json). A push split on one client has to reassemble
 * on the other, and the failure is silent at both ends: the network drops an
 * oversized message without telling the publisher, and a reader that accepted
 * a partial run would merge truncated JSON into the account's state.
 */
class SyncChunksTest {

    private fun vectors(): JSONObject {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "docs/SYNC-chunk-vectors.json")
            if (candidate.isFile) return JSONObject(candidate.readText())
            dir = dir.parentFile
        }
        throw AssertionError("parity vectors not found: docs/SYNC-chunk-vectors.json")
    }

    private fun list(arr: JSONArray) =
        (0 until arr.length()).map { arr.getJSONObject(it) }

    /** Field order is not part of the format; compare the meaning. */
    private fun same(a: JSONObject, b: JSONObject): Boolean =
        a.length() == b.length() && a.keys().asSequence().all { k ->
            val x = a.get(k); val y = b.opt(k)
            when {
                x is JSONObject && y is JSONObject -> same(x, y)
                x is JSONArray && y is JSONArray -> x.toString() == y.toString()
                else -> x.toString() == y?.toString()
            }
        }

    private fun quotedBytes(s: String) = JSONObject.quote(s).toByteArray(Charsets.UTF_8).size

    /**
     * Field ORDER is not part of the format — org.json does not keep insertion
     * order and JSON.stringify does — and the two serialisers escape different
     * characters, so each client slices its own serialisation at its own
     * points. What has to match is the framing: which messages, numbered how,
     * each within the budget, and that the run still carries the whole
     * snapshot.
     */
    @Test
    fun `frames a snapshot the same way the web does`() {
        val v = vectors()
        val limit = v.getInt("limit")
        for (case in list(v.getJSONArray("split"))) {
            val what = case.getString("what")
            val expected = list(case.getJSONArray("messages"))
            val payload = case.getJSONObject("payload")
            val got = SyncChunks.split(payload, "runA", limit)

            assertEquals("$what — message types",
                expected.map { it.optString("type") }.toSet(), got.map { it.optString("type") }.toSet())
            if (expected.size == 1) {
                assertEquals("$what — a snapshot that fits stays whole", 1, got.size)
                continue
            }

            val chunks = got.filter { it.optString("type") == "sync_chunk" }
            assertEquals("$what — chunk numbering",
                chunks.indices.toList(), chunks.map { it.optInt("chunkIndex") })
            assertTrue("$what — a chunk went over the budget",
                chunks.all { quotedBytes(it.getString("data")) <= limit })
            assertTrue("$what — a chunk ends in half a surrogate pair",
                chunks.none { Character.isHighSurrogate(it.getString("data").last()) })
            got.lastOrNull()?.takeIf { it.optString("type") == "sync_manifest" }?.let {
                assertEquals("$what — manifest count", chunks.size, it.optInt("chunkCount"))
            }
            if (chunks.isNotEmpty()) {
                val joined = chunks.joinToString("") { it.getString("data") }
                assertTrue("$what — the run lost part of the snapshot",
                    same(payload, JSONObject(joined)))
            }
        }
    }

    @Test
    fun `reassembles exactly as the web does`() {
        val v = vectors()
        for (case in list(v.getJSONArray("reassemble"))) {
            val expected = list(case.getJSONArray("payloads"))
            val got = SyncChunks.reassemble(list(case.getJSONArray("messages")))
            assertEquals(case.getString("what"), expected.size, got.size)
            for (want in expected) {
                assertTrue(
                    "${case.getString("what")} — missing payload $want",
                    got.any { same(want, it) }
                )
            }
        }
    }

    @Test
    fun `a round trip is lossless`() {
        val payload = JSONObject()
            .put("type", "sync").put("v", 1).put("ts", 1789000000000L)
            .put("data", JSONObject().put("note", "w".repeat(5000)))

        val back = SyncChunks.reassemble(SyncChunks.split(payload, "run1", 250))

        assertEquals(1, back.size)
        assertTrue(same(payload, back[0]))
    }

    @Test
    fun `no chunk carries more than the budget, counted as JSON writes it`() {
        for (fill in listOf("z".repeat(5000), "\"\\/".repeat(1500), "ação coração ".repeat(400),
                "🐦".repeat(2000), "\u0001\n".repeat(1500))) {
            val payload = JSONObject()
                .put("type", "sync").put("v", 1).put("ts", 1L)
                .put("data", JSONObject().put("note", fill))

            val out = SyncChunks.split(payload, "run1", 300)
            for (m in out) {
                if (m.optString("type") != "sync_chunk") continue
                assertTrue("a chunk went over the budget", quotedBytes(m.getString("data")) <= 300)
            }
            assertTrue(same(payload, SyncChunks.reassemble(out).single()))
        }
    }

    @Test
    fun `cuts accented text into more, shorter chunks than the same length of ASCII`() {
        fun count(fill: String) = SyncChunks.split(
            JSONObject().put("type", "sync").put("v", 1).put("ts", 1L).put("data", JSONObject().put("n", fill)),
            "run1", 300).size
        assertTrue(count("ç".repeat(3000)) > count("c".repeat(3000)))
    }

    @Test
    fun `keeps every sealed chunk under the wire budget, whatever the text is made of`() {
        val me = "0x8f2a559490d8e9bb4e0e7b53e1c6e4c2b1a0d9c8b7a6958473625140fedcba98"
        val address = EthereumSigner.address(me)
        val pub = EthereumSigner.compressedPublicKey(me)
        // config.media.imagePayloadMaxBytes less its safety margin
        val wireBudget = 220 * 1024 - 1024
        for (fill in listOf("🐦".repeat(90000), "\"".repeat(200000), "ç".repeat(160000),
                "/".repeat(200000), "\u0001".repeat(60000))) {
            val payload = JSONObject().put("type", "sync").put("v", 1).put("ts", 1L)
                .put("data", JSONObject().put("n", fill))
            val chunks = SyncChunks.split(payload, "run1").filter { it.optString("type") == "sync_chunk" }
            assertTrue(chunks.size > 1)
            for (chunk in chunks) {
                val (envelope, _) = SealedSenderCrypto.seal(chunk, me, address, pub)
                assertTrue("a sealed chunk went over the wire budget",
                    envelope.toString().toByteArray(Charsets.UTF_8).size <= wireBudget)
            }
        }
    }

    @Test
    fun `never cuts an emoji in half, which sealing would turn into ?`() {
        val limit = 50
        val text = "p" + "🐦".repeat(60)
        val payload = JSONObject().put("t", text)

        val chunks = SyncChunks.split(payload, "run1", limit).filter { it.optString("type") == "sync_chunk" }

        assertTrue(chunks.size > 2)
        for (c in chunks) {
            val data = c.getString("data")
            assertTrue("chunk ${c.optInt("chunkIndex")} ends in half a pair", !Character.isHighSurrogate(data.last()))
            assertTrue("chunk ${c.optInt("chunkIndex")} starts with half a pair", !Character.isLowSurrogate(data.first()))
        }
        assertEquals(text, JSONObject(chunks.joinToString("") { it.getString("data") }).getString("t"))
    }

    @Test
    fun `an incomplete run is reported, not half applied`() {
        val payload = JSONObject()
            .put("type", "sync").put("v", 1).put("ts", 1L)
            .put("data", JSONObject().put("note", "p".repeat(2000)))
        val run = SyncChunks.split(payload, "run1", 250)

        val dropped = mutableListOf<SyncChunks.Dropped>()
        val got = SyncChunks.reassemble(run.drop(1)) { dropped.add(it) }

        assertTrue(got.isEmpty())
        assertEquals("run1", dropped.single().syncId)
        assertEquals("incomplete", dropped.single().reason)
    }

    @Test
    fun `the budgets match the web's`() {
        assertEquals(150 * 1024, SyncChunks.CHUNK_CHARS)
        assertEquals(150 * 1024, SyncChunks.CHUNK_BYTES)
    }
}
