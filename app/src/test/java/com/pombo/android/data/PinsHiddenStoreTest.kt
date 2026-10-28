package com.pombo.android.data

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A closed pinned banner is remembered per account; a guest's is never written. */
class PinsHiddenStoreTest {

    private val stored = HashMap<String, String>()
    private lateinit var context: Context

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
        every { editor.remove(capture(key)) } answers { stored.remove(key.captured); editor }
        context = mockk(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
    }

    @Test
    fun `closing survives a restart and stays with its account`() {
        PinsHiddenStore(context).apply { scopeAddress = "0xBob" }.hide("s1", "m3")

        val again = PinsHiddenStore(context).apply { scopeAddress = "0xbob" }
        assertEquals(mapOf("s1" to "m3"), again.hidden.value)

        again.scopeAddress = "0xAlice"
        assertTrue(again.hidden.value.isEmpty())
    }

    @Test
    fun `show forgets the channel`() {
        val s = PinsHiddenStore(context).apply { scopeAddress = "0xBob" }
        s.hide("s1", "m3")
        s.show("s1")

        assertTrue(PinsHiddenStore(context).apply { scopeAddress = "0xBob" }.hidden.value.isEmpty())
    }

    @Test
    fun `a guest closes in memory only`() {
        val s = PinsHiddenStore(context).apply { memoryOnly = true; scopeAddress = "0xGuest" }
        s.hide("s1", "m3")

        assertEquals(mapOf("s1" to "m3"), s.hidden.value)
        assertTrue(stored.isEmpty())
    }

    @Test
    fun `clearAccount drops what the account closed`() {
        val s = PinsHiddenStore(context).apply { scopeAddress = "0xBob" }
        s.hide("s1", "m3")
        s.clearAccount()

        assertTrue(s.hidden.value.isEmpty())
        assertTrue(stored.isEmpty())
    }
}
