package com.pombo.android.core

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Framing for an ADMIN_STATE too big for one wire message, locked by
 * web-generated vectors (Pombo Web tests/vectors/gen_admin_chunk_vectors.mjs,
 * docs/ADMIN-chunk-vectors.json). A snapshot split by the owner on one client
 * has to reassemble on the other, or its members keep the previous moderation.
 */
class AdminChunksTest {

    private fun vectors(): JSONObject {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "docs/ADMIN-chunk-vectors.json")
            if (candidate.isFile) return JSONObject(candidate.readText())
            dir = dir.parentFile
        }
        throw AssertionError("parity vectors not found: docs/ADMIN-chunk-vectors.json")
    }

    private fun list(arr: JSONArray) =
        (0 until arr.length()).map { arr.getJSONObject(it) }

    /** Field order is not part of the format; compare the meaning. */
    private fun same(a: JSONObject, b: JSONObject): Boolean =
        a.length() == b.length() && a.keys().asSequence().all { k ->
            val x = a.get(k); val y = b.opt(k)
            when {
                x is JSONObject && y is JSONObject -> same(x, y)
                x is JSONArray && y is JSONArray -> x.length() == y.length() &&
                    (0 until x.length()).all { i ->
                        val p = x.get(i); val q = y.get(i)
                        if (p is JSONObject && q is JSONObject) same(p, q) else p.toString() == q.toString()
                    }
                else -> x.toString() == y?.toString()
            }
        }

    /**
     * Each client slices its own serialisation, so the cut points differ;
     * what has to match is the framing: how many rows, of which types,
     * numbered how, rev and ts on every row, and the whole snapshot carried.
     */
    @Test
    fun `frames a snapshot the same way the web does`() {
        val v = vectors()
        val limit = v.getInt("limit")
        for (case in list(v.getJSONArray("split"))) {
            val what = case.getString("what")
            val expected = list(case.getJSONArray("messages"))
            val payload = case.getJSONObject("payload")
            val got = SyncChunks.splitFramed(payload, "runA", SyncChunks.ADMIN, limit)

            assertEquals("$what — message types",
                expected.map { it.optString("type") }.toSet(), got.map { it.optString("type") }.toSet())
            if (expected.size == 1) {
                assertEquals("$what — a snapshot that fits stays whole", 1, got.size)
                continue
            }
            val chunks = got.filter { it.optString("type") == "admin_chunk" }
            assertEquals("$what — chunk numbering",
                chunks.indices.toList(), chunks.map { it.optInt("chunkIndex") })
            assertTrue("$what — a chunk went over the budget",
                chunks.all { it.getString("data").length <= limit })
            assertEquals("$what — manifest last, counting the chunks",
                chunks.size, got.last().takeIf { it.optString("type") == "admin_manifest" }?.optInt("chunkCount"))
            assertTrue("$what — rev and ts on every row", got.all {
                it.optLong("rev") == payload.optLong("rev") && it.optLong("ts") == payload.optLong("ts")
            })
            val joined = chunks.joinToString("") { it.getString("data") }
            assertTrue("$what — the run lost part of the snapshot", same(payload, JSONObject(joined)))
        }
    }

    @Test
    fun `reassembles exactly as the web does`() {
        val v = vectors()
        for (case in list(v.getJSONArray("reassemble"))) {
            val expected = list(case.getJSONArray("payloads"))
            val got = SyncChunks.joinFramed(list(case.getJSONArray("messages")), SyncChunks.ADMIN)
                .map { it.payload }
            assertEquals(case.getString("what"), expected.size, got.size)
            for (want in expected) {
                assertTrue("${case.getString("what")} — missing payload $want", got.any { same(want, it) })
            }
        }
    }

    @Test
    fun `never cuts between the two halves of a surrogate pair`() {
        val limit = 50
        // {"t":" is six characters: the emoji's high half lands on the last slot of the first cut.
        val text = "p".repeat(limit - 1 - 6) + "🐦" + "q".repeat(120)
        val payload = JSONObject().put("t", text)

        val chunks = SyncChunks.splitFramed(payload, "r", SyncChunks.ADMIN, limit)
            .filter { it.optString("type") == "admin_chunk" }

        assertEquals(limit - 1, chunks[0].getString("data").length)
        for (c in chunks) {
            val data = c.getString("data")
            assertFalse("chunk ${c.optInt("chunkIndex")} ends in half a pair",
                Character.isHighSurrogate(data.last()))
            assertFalse("chunk ${c.optInt("chunkIndex")} starts with half a pair",
                Character.isLowSurrogate(data.first()))
        }
        assertEquals(text, JSONObject(chunks.joinToString("") { it.getString("data") }).getString("t"))
    }

    @Test
    fun `the sync framing is unchanged`() {
        val payload = JSONObject().put("type", "sync").put("v", 1).put("ts", 7L)
            .put("data", JSONObject().put("note", "s".repeat(700)))

        val out = SyncChunks.split(payload, "run1", 200)

        assertTrue(out.dropLast(1).all { it.optString("type") == "sync_chunk" && it.optString("syncId") == "run1" })
        assertEquals("sync_manifest", out.last().optString("type"))
        assertTrue(out.all { it.optLong("ts") == 7L && !it.has("rev") && !it.has("runId") })
    }
}
