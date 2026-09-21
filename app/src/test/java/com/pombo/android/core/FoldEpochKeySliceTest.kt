package com.pombo.android.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A channel's key slice is not just its epoch keys. The import used to copy
 * three fields and drop the rest whenever the device already had state, so a
 * Sealed channel never received the shared publish key it needs to open
 * authorship — and its history stayed unreadable on that device alone.
 */
class FoldEpochKeySliceTest {

    private fun rev(id: String, rev: Int) = JSONObject().put("keyId", id).put("rev", rev)

    @Test
    fun `the shared publish key survives the fold`() {
        val local = JSONObject().put("epochs", JSONObject())
        val incoming = JSONObject()
            .put("pubKey", rev("p2.bbbb", 2))
            .put("pubAnnounce", rev("p2.bbbb", 2))

        val out = SyncMerge.foldEpochKeySlice(local, incoming)

        assertEquals("p2.bbbb", out.getJSONObject("pubKey").getString("keyId"))
        assertEquals("p2.bbbb", out.getJSONObject("pubAnnounce").getString("keyId"))
    }

    @Test
    fun `a re-key supersedes and an older one never regresses`() {
        val local = JSONObject().put("pubKey", rev("p2.bbbb", 2))
        SyncMerge.foldEpochKeySlice(local, JSONObject().put("pubKey", rev("p1.aaaa", 1)))
        assertEquals("an older rev must not win", "p2.bbbb", local.getJSONObject("pubKey").getString("keyId"))

        SyncMerge.foldEpochKeySlice(local, JSONObject().put("pubKey", rev("p3.cccc", 3)))
        assertEquals("p3.cccc", local.getJSONObject("pubKey").getString("keyId"))
    }

    @Test
    fun `the interactions key travels too`() {
        val local = JSONObject()
        val out = SyncMerge.foldEpochKeySlice(
            local,
            JSONObject().put("intKey", rev("i1.dddd", 1)).put("intAnnounce", rev("i1.dddd", 1))
        )
        assertEquals("i1.dddd", out.getJSONObject("intKey").getString("keyId"))
        assertEquals("i1.dddd", out.getJSONObject("intAnnounce").getString("keyId"))
    }

    @Test
    fun `content keys union and an adopted one never regresses`() {
        val local = JSONObject()
            .put("epochs", JSONObject().put("1.aaa", JSONObject().put("keyHex", "0xlocal")))
            .put("announces", JSONObject().put("1", JSONObject().put("keyId", "1.aaa")))
            .put("currentEpoch", 1)
        val incoming = JSONObject()
            .put("epochs", JSONObject()
                .put("1.aaa", JSONObject().put("keyHex", "0xremote"))
                .put("2.bbb", JSONObject().put("keyHex", "0xnew")))
            .put("announces", JSONObject().put("2", JSONObject().put("keyId", "2.bbb")))
            .put("currentEpoch", 2)

        val out = SyncMerge.foldEpochKeySlice(local, incoming)

        assertEquals("0xlocal", out.getJSONObject("epochs").getJSONObject("1.aaa").getString("keyHex"))
        assertEquals("0xnew", out.getJSONObject("epochs").getJSONObject("2.bbb").getString("keyHex"))
        assertTrue("the announce that judges epoch 2 has to arrive with its key",
            out.getJSONObject("announces").has("2"))
        assertEquals(2, out.getInt("currentEpoch"))
    }

    @Test
    fun `hello epochs union instead of replacing`() {
        val local = JSONObject().put("helloEpochs", JSONArray(listOf(1, 3)))
        val out = SyncMerge.foldEpochKeySlice(local, JSONObject().put("helloEpochs", JSONArray(listOf(3, 5))))
        val got = (0 until out.getJSONArray("helloEpochs").length())
            .map { out.getJSONArray("helloEpochs").getInt(it) }
        assertEquals(listOf(1, 3, 5), got)
    }

    @Test
    fun `nothing is invented when the slice carries nothing`() {
        val out = SyncMerge.foldEpochKeySlice(JSONObject(), JSONObject())
        assertNull(out.optJSONObject("pubKey"))
        assertNull(out.optJSONObject("intKey"))
    }
}
