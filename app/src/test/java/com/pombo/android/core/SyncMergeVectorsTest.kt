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

    private fun copy(value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject(value.toString())
        is JSONArray -> JSONArray(value.toString())
        else -> value
    }

    private fun at(root: Any?, path: JSONArray, upTo: Int = path.length()): Any? {
        var node = root
        for (i in 0 until upTo) {
            node = when (node) {
                is JSONObject -> node.opt(path.getString(i))
                is JSONArray -> node.opt(path.getInt(i))
                else -> null
            }
        }
        return node
    }

    private fun place(parent: Any?, key: Any, value: Any?) {
        when (parent) {
            is JSONObject -> parent.put(key.toString(), value ?: JSONObject.NULL)
            is JSONArray -> parent.put((key as Number).toInt(), value ?: JSONObject.NULL)
        }
    }

    /** The publish cases are patches on a shared base (see the web generator). */
    private fun applyPatch(state: JSONObject, patch: JSONObject?): JSONObject {
        var out = JSONObject(state.toString())
        if (patch == null) return out
        patch.optJSONArray("set")?.let { sets ->
            for (i in 0 until sets.length()) {
                val entry = sets.getJSONArray(i)
                val path = entry.getJSONArray(0)
                place(at(out, path, path.length() - 1), path.get(path.length() - 1), copy(entry.opt(1)))
            }
        }
        patch.optJSONArray("reverse")?.let { paths ->
            for (i in 0 until paths.length()) {
                val path = paths.getJSONArray(i)
                val arr = at(out, path) as JSONArray
                val reversed = JSONArray((arr.length() - 1 downTo 0).map { arr.opt(it) })
                place(at(out, path, path.length() - 1), path.get(path.length() - 1), reversed)
            }
        }
        patch.optJSONArray("reverseKeys")?.let { paths ->
            for (i in 0 until paths.length()) {
                val path = paths.getJSONArray(i)
                val obj = at(out, path) as JSONObject
                val reversed = JSONObject()
                obj.keys().asSequence().toList().asReversed().forEach { reversed.put(it, obj.get(it)) }
                if (path.length() == 0) out = reversed
                else place(at(out, path, path.length() - 1), path.get(path.length() - 1), reversed)
            }
        }
        return out
    }

    @Test
    fun `only news triggers a push, as on the web`() {
        val publish = vectors().getJSONObject("publish")
        val cases = publish.getJSONArray("cases")
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val from = applyPatch(publish.getJSONObject("base"), case.optJSONObject("basePatch"))
            val same = SyncStateKey.key(from) == SyncStateKey.key(applyPatch(from, case.optJSONObject("patch")))
            assertTrue("${case.getString("what")}: push ${if (same) "skipped" else "sent"}", same == case.getBoolean("same"))
        }
    }

    @Test
    fun `channel records merge field by field as on the web`() {
        val cases = vectors().getJSONArray("channels")
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val merged = SyncMerge.mergeState(case.getJSONObject("base"), case.getJSONObject("incoming"))
            val expected = case.getJSONObject("expected")
            for (key in listOf("channels", "channelsLeftAt")) {
                assertTrue(
                    "${case.getString("what")}: $key was ${merged.opt(key)}, expected ${expected.opt(key)}",
                    same(merged.opt(key), expected.opt(key))
                )
            }
        }
    }

    @Test
    fun `sent DMs carry deletions and edits as on the web`() {
        val cases = vectors().getJSONArray("sent")
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val merged = SyncMerge.mergeState(case.getJSONObject("base"), case.getJSONObject("incoming"))
            val expected = case.getJSONObject("expected")
            for (key in listOf("sentMessages", "sentDeletedAt")) {
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
