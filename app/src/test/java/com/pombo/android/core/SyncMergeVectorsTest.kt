package com.pombo.android.core

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice resolution locked by web-generated vectors (Pombo Web
 * tests/vectors/gen_sync_merge_vectors.mjs, docs/SYNC-merge-vectors.json).
 * Both clients merge the same payloads, so resolving a slice to a different
 * side leaves the account's devices disagreeing about who is blocked or what
 * the account is called, and the wrong pick can erase a value everywhere.
 */
class SyncMergeVectorsTest {

    private val slices = listOf("blockedPeers", "dmLeftAt", "trustedContacts", "username", "graphApiKey")

    private fun vectors(): JSONObject {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "docs/SYNC-merge-vectors.json")
            if (candidate.isFile) return JSONObject(candidate.readText())
            dir = dir.parentFile
        }
        throw AssertionError("parity vectors not found: docs/SYNC-merge-vectors.json")
    }

    /** Field order is not part of the format; compare the meaning. */
    private fun same(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject ->
            a.length() == b.length() && a.keys().asSequence().all { same(a.get(it), b.opt(it)) }
        a is JSONArray && b is JSONArray ->
            a.length() == b.length() && (0 until a.length()).all { same(a.get(it), b.get(it)) }
        else -> a.toString() == b?.toString()
    }

    @Test
    fun `slices resolve to the same side as on the web`() {
        val cases = vectors().getJSONArray("merge")
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val merged = SyncMerge.mergeState(case.getJSONObject("base"), case.getJSONObject("incoming"))
            val expected = case.getJSONObject("expected")
            for (key in slices + "sliceTs") {
                assertTrue(
                    "${case.getString("what")}: $key was ${merged.opt(key)}, expected ${expected.opt(key)}",
                    same(merged.opt(key), expected.opt(key))
                )
            }
        }
    }

    @Test
    fun `unstamped values are stamped as on the web`() {
        val cases = vectors().getJSONArray("stamp")
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val stamped = SyncMerge.stampedSliceTs(case.getJSONObject("state"))
            assertTrue(
                "${case.getString("what")}: got $stamped",
                same(stamped, case.getJSONObject("sliceTs"))
            )
        }
    }
}
