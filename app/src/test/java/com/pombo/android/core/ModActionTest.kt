package com.pombo.android.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Parity with the web's modAction.js and modComposition.js, read straight from
 * the web-generated vector files instead of transcribed — a copy drifts
 * silently, a file cannot.
 */
class ModActionTest {

    private fun vectors(name: String): JSONObject? {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "docs/$name")
            if (candidate.isFile) return JSONObject(candidate.readText())
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun `verifies every web-generated delta and recovers the moderator`() {
        val v = vectors("GATED-CHANNELS-mod-action-vectors.json")
        assumeTrue("mod-action vectors not found", v != null)
        val streamId = v!!.getString("streamId")
        val mod = v.getString("mod").lowercase()
        val cases = v.getJSONArray("vectors")
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val delta = cases.getJSONObject(i).getJSONObject("delta")
            assertEquals("delta $i", mod, ModAction.verify(streamId, delta))
        }
    }

    @Test
    fun `digest matches the web byte for byte`() {
        val v = vectors("GATED-CHANNELS-mod-action-vectors.json")
        assumeTrue("mod-action vectors not found", v != null)
        val streamId = v!!.getString("streamId")
        val cases = v.getJSONArray("vectors")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val delta = case.getJSONObject("delta")
            val sinceEpoch = if (delta.has("sinceEpoch")) delta.getInt("sinceEpoch") else null
            val digest = ModAction.digest(
                streamId, delta.getString("op"), delta.getString("target"),
                sinceEpoch, delta.getLong("ts"))
            assertEquals("digest $i", case.getString("digest"), EthereumSigner.toHex(digest))
        }
    }

    @Test
    fun `android signs deltas the web would accept`() {
        val v = vectors("GATED-CHANNELS-mod-action-vectors.json")
        assumeTrue("mod-action vectors not found", v != null)
        val streamId = v!!.getString("streamId")
        val priv = v.getString("modPriv")
        val web = v.getJSONArray("vectors").getJSONObject(2).getJSONObject("delta")

        val mine = ModAction.build(
            streamId, web.getString("op"), web.getString("target"),
            priv, web.getInt("sinceEpoch"), web.getLong("ts"))
        // RFC 6979 on both sides: same key, same digest, same signature.
        assertEquals(web.getString("sig"), mine.getString("sig"))
        assertEquals(v.getString("mod").lowercase(), ModAction.verify(streamId, mine))
    }

    @Test
    fun `a tampered field stops the delta verifying`() {
        val v = vectors("GATED-CHANNELS-mod-action-vectors.json")
        assumeTrue("mod-action vectors not found", v != null)
        val streamId = v!!.getString("streamId")
        val delta = JSONObject(v.getJSONArray("vectors").getJSONObject(0)
            .getJSONObject("delta").toString())
        delta.put("target", "msg-someone-else")
        assertNull(ModAction.verify(streamId, delta))
    }

    @Test
    fun `a delta signed for another stream does not verify here`() {
        val v = vectors("GATED-CHANNELS-mod-action-vectors.json")
        assumeTrue("mod-action vectors not found", v != null)
        val delta = v!!.getJSONArray("vectors").getJSONObject(0).getJSONObject("delta")
        assertNotNull(ModAction.verify(v.getString("streamId"), delta))
        assertNull(ModAction.verify("0xdead/other-1", delta))
    }

    @Test
    fun `composition matches the web on every case`() {
        val v = vectors("GATED-CHANNELS-mod-composition-vectors.json")
        assumeTrue("mod-composition vectors not found", v != null)
        val cases = v!!.getJSONArray("cases")
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val snapshot = case.getJSONObject("snapshot")
            val deltas = case.getJSONArray("deltas").let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it) }
            }
            val mods = case.getJSONArray("modsNow").let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            }
            val hidden = snapshot.optJSONArray("hiddenMessageIds")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }.toSet()
            } ?: emptySet()

            val effective = ModComposition.compose(
                hidden,
                ModComposition.bannedFromJson(snapshot.optJSONArray("bannedMembers")),
                snapshot.optLong("absorbedThrough"),
                deltas, mods)

            val expected = case.getJSONObject("effective")
            val what = case.optString("what")
            assertEquals(what, expectedHidden(expected), effective.hiddenMessageIds.sorted())
            assertEquals(what, expectedBanned(expected), effective.bannedMembers.toSortedMap().toString())
        }
    }

    private fun expectedHidden(effective: JSONObject): List<String> {
        val arr = effective.optJSONArray("hiddenMessageIds") ?: JSONArray()
        return (0 until arr.length()).map { arr.getString(it) }.sorted()
    }

    private fun expectedBanned(effective: JSONObject): String {
        val arr = effective.optJSONArray("bannedMembers") ?: JSONArray()
        return ModComposition.bannedFromJson(arr).toSortedMap().toString()
    }

    @Test
    fun `a ban hides only from its epoch onward`() {
        assertTrue(ModComposition.banHides(null, 4))
        assertTrue(ModComposition.banHides(null, null))
        assertTrue(ModComposition.banHides(3, 3))
        assertTrue(ModComposition.banHides(3, 9))
        assertTrue(!ModComposition.banHides(3, 2))
        // Unknown epoch: keep the message rather than hide on a guess.
        assertTrue(!ModComposition.banHides(3, null))
    }
}
