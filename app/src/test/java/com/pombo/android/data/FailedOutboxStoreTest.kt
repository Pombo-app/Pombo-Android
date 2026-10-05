package com.pombo.android.data

import android.content.Context
import android.content.SharedPreferences
import com.pombo.android.core.SecurePrefs
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The outbox over an in-memory SharedPreferences. */
class FailedOutboxStoreTest {

    private val room = "0xme/room-1"
    private val stored = HashMap<String, String>()

    @Before
    fun setUp() {
        mockkObject(SecurePrefs)
        every { SecurePrefs.create(any(), any(), any()) } returns inMemoryPrefs(stored)
    }

    @After
    fun tearDown() = unmockkObject(SecurePrefs)

    private fun store(scope: String = "0xme") = FailedOutboxStore(mockk<Context>(relaxed = true)).apply { scopeAddress = scope }

    private fun entry(id: String, timestamp: Long = 100L) =
        JSONObject().put("id", id).put("type", "text").put("text", "text of $id").put("timestamp", timestamp)

    private fun ids(store: FailedOutboxStore) = store.load(room).map { it.getString("id") }

    @Test
    fun `an entry is replaced by id, not duplicated`() {
        val s = store()
        s.put(room, entry("a").put("failError", "first"))
        s.put(room, entry("a").put("failError", "second"))

        assertEquals(listOf("a"), ids(s))
        assertEquals("second", s.load(room).single().getString("failError"))
    }

    @Test
    fun `past the cap the oldest entries go`() {
        val s = store()
        for (i in 1..FailedOutboxStore.MAX_PER_CONVERSATION + 1) s.put(room, entry("m$i", timestamp = i.toLong()))

        assertEquals(FailedOutboxStore.MAX_PER_CONVERSATION, ids(s).size)
        assertEquals("m2", ids(s).first())
        assertEquals("m${FailedOutboxStore.MAX_PER_CONVERSATION + 1}", ids(s).last())
    }

    @Test
    fun `remove and clear drop entries`() {
        val s = store()
        s.put(room, entry("a"))
        s.put(room, entry("b"))

        s.remove(room, "a")
        assertEquals(listOf("b"), ids(s))
        s.clear(room)
        assertTrue(ids(s).isEmpty())
    }

    @Test
    fun `each account sees only its own outbox, and a guest keeps nothing`() {
        store("0xme").put(room, entry("a"))

        assertTrue(store("0xother").load(room).isEmpty())
        val guest = store().apply { memoryOnly = true }
        guest.put(room, entry("b"))
        assertEquals(listOf("a"), ids(store("0xme")))
    }

    companion object {
        /** SharedPreferences over a map: getString, putString and remove. */
        fun inMemoryPrefs(stored: MutableMap<String, String>): SharedPreferences {
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
            every { editor.remove(capture(key)) } answers {
                stored.remove(key.captured)
                editor
            }
            return prefs
        }
    }
}
