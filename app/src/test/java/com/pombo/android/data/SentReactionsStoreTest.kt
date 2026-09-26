package com.pombo.android.data

import android.content.Context
import android.content.SharedPreferences
import com.pombo.android.core.SecurePrefs
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A reaction that is already held, or removed when absent, changes nothing:
 * it must not ask the sync for a push nor leave an empty entry behind.
 */
class SentReactionsStoreTest {

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

    private fun store() = SentReactionsStore(mockk<Context>(relaxed = true)).apply { scopeAddress = "0xme" }

    @Test
    fun `a reaction held already or removed when absent changes nothing`() {
        val s = store()

        assertFalse(s.record(dm, "m1", "👍", "0xAbc", add = false))
        assertNull(s.forStream(dm))

        assertTrue(s.record(dm, "m1", "👍", "0xAbc", add = true))
        assertFalse(s.record(dm, "m1", "👍", "0xabc", add = true))
        assertEquals(1, s.forStream(dm)!!.getJSONObject("m1").getJSONArray("👍").length())

        assertTrue(s.record(dm, "m1", "👍", "0xabc", add = false))
        assertNull(s.forStream(dm))
    }
}
