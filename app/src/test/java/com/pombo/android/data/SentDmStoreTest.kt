package com.pombo.android.data

import android.content.Context
import android.content.SharedPreferences
import com.pombo.android.core.SecurePrefs
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The store over an in-memory SharedPreferences. A sent DM exists only in this
 * store and in the sync, so a deletion recorded here is the only thing that
 * stops another device's copy from bringing it back.
 */
class SentDmStoreTest {

    private val dm = "0xpeer/Pombo-DM-1"
    private val stored = HashMap<String, String>()

    @Before
    fun setUp() {
        val prefs: SharedPreferences = mockk(relaxed = true)
        val editor: SharedPreferences.Editor = mockk(relaxed = true)
        val key = slot<String>()
        val value = slot<String>()
        every { prefs.getString(capture(key), any()) } answers { stored[key.captured] }
        every { prefs.edit() } returns editor
        every { editor.putString(capture(key), capture(value)) } answers {
            stored[key.captured] = value.captured
            editor
        }
        mockkObject(SecurePrefs)
        every { SecurePrefs.create(any(), any(), any()) } returns prefs
    }

    @After
    fun tearDown() = unmockkObject(SecurePrefs)

    private fun store() = SentDmStore(mockk<Context>(relaxed = true)).apply { scopeAddress = "0xme" }

    private fun text(id: String, extra: Map<String, Any> = emptyMap()) =
        JSONObject().put("id", id).put("type", "text").put("text", "text of $id").put("timestamp", 100L)
            .also { m -> extra.forEach { (k, v) -> m.put(k, v) } }

    private fun ids(store: SentDmStore) = store.load(dm).map { it.getString("id") }

    private fun slice(vararg messages: JSONObject) =
        JSONObject().put(dm, JSONArray().also { a -> messages.forEach { a.put(it) } })

    @Test
    fun `deleting drops the local copy and records the deletion`() {
        val s = store()
        s.add(dm, text("m1"))
        s.add(dm, text("m2"))

        s.delete(dm, "m2")

        assertEquals(listOf("m1"), ids(s))
        assertTrue(s.deleted().getJSONObject(dm).has("m2"))
    }

    @Test
    fun `a deletion from another device drops the copy held here and joins the record`() {
        val s = store()
        s.add(dm, text("m1"))
        s.add(dm, text("m2"))
        s.delete(dm, "m1")

        s.importDeleted(JSONObject().put(dm, JSONObject().put("m2", 5000L)))

        assertEquals(emptyList<String>(), ids(s))
        val record = s.deleted().getJSONObject(dm)
        assertTrue(record.has("m1"))
        assertEquals(5000L, record.getLong("m2"))
    }

    @Test
    fun `a synced copy of a deleted message is not brought back`() {
        val s = store()
        s.add(dm, text("m1"))
        s.delete(dm, "m1")

        s.importAll(slice(text("m1"), text("m2")))

        assertEquals(listOf("m2"), ids(s))
    }

    @Test
    fun `an edit records its time and a later synced edit replaces it`() {
        val s = store()
        s.add(dm, text("m1"))
        s.edit(dm, "m1", "first", 3000L)
        assertEquals(3000L, s.load(dm).single().getLong("_editedAt"))

        s.importAll(slice(text("m1", mapOf("text" to "older", "_edited" to true, "_editedAt" to 2000L))))
        assertEquals("first", s.load(dm).single().getString("text"))

        s.importAll(slice(text("m1", mapOf("text" to "second", "_edited" to true, "_editedAt" to 4000L))))
        assertEquals("second", s.load(dm).single().getString("text"))
    }
}
