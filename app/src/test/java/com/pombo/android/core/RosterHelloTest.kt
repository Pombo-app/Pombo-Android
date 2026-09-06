package com.pombo.android.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The roster's name rules, read from the same web-generated vectors the web
 * suite runs (docs/GATED-CHANNELS-hello-vectors.json).
 *
 * The rule that is easy to get wrong: a hello WITHOUT a name does not erase
 * the name an older one carried.
 */
class RosterHelloTest {

    /**
     * Missing vectors used to skip this test, which made the suite green while
     * the parity check ran on nothing. Absent is now a failure.
     */
    private fun vectors(): JSONObject {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "docs/GATED-CHANNELS-hello-vectors.json")
            if (candidate.isFile) return JSONObject(candidate.readText())
            dir = dir.parentFile
        }
        throw AssertionError("parity vectors not found: docs/GATED-CHANNELS-hello-vectors.json")
    }

    private fun rosterOf(hellos: List<JSONObject>): List<EpochKeyManager.RosterMember> {
        val members = LinkedHashMap<String, EpochKeyManager.RosterMember>()
        val nameTs = HashMap<String, Long>()
        for (hello in hellos) {
            EpochKeyManager.mergeHello(
                members, nameTs, hello.getString("account").lowercase(),
                hello, hello.optLong("ts"))
        }
        return members.values.sortedBy { it.account }
    }

    @Test
    fun `composes the roster exactly like the web`() {
        val v = vectors()
        val cases = v.getJSONArray("vectors")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val hellos = case.getJSONArray("hellos").let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it) }
            }
            val expected = case.getJSONArray("roster").let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .sortedBy { it.getString("account") }
            }
            val actual = rosterOf(hellos)
            val what = case.optString("what")
            assertEquals(what, expected.size, actual.size)
            expected.forEachIndexed { idx, want ->
                val got = actual[idx]
                assertEquals(what, want.getString("account"), got.account)
                assertEquals(what, want.getLong("ts"), got.ts)
                assertEquals(what, want.optString("spk").ifEmpty { null }, got.spk)
                assertEquals(what, want.optString("name").ifEmpty { null }, got.name)
            }
        }
    }

    @Test
    fun `a hello with no name never erases one`() {
        val account = "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266"
        val named = JSONObject().put("account", account).put("ts", 100L).put("name", "Alice")
        val silent = JSONObject().put("account", account).put("ts", 200L)
        assertEquals("Alice", rosterOf(listOf(named, silent)).single().name)
        assertEquals("Alice", rosterOf(listOf(silent, named)).single().name)
        assertEquals(200L, rosterOf(listOf(named, silent)).single().ts)
    }
}
