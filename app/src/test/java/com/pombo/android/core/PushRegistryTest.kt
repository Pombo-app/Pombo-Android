package com.pombo.android.core

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry over an in-memory SharedPreferences: what these cover is the
 * persisted shape, which is the only part a device upgrade can break.
 */
class PushRegistryTest {

    private val stored = HashMap<String, String>()

    private fun registry(address: String? = "0xme"): PushRegistry {
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
        val context: Context = mockk(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns prefs
        return PushRegistry(context).apply { scopeAddress = address }
    }

    private fun entry(streamId: String, tag: String) = PushRegistry.Entry(
        streamId = streamId, tag = tag, type = "gated", name = "C", lastTimestamp = 1L
    )

    @Test
    fun `remembers where the chain says a stream is stored, and when it was asked`() {
        val r = registry()
        assertEquals(0L, r.providersCheckedAt("0xa/one-1"))

        r.rememberProviders("0xa/one-1", listOf("https://new.test"))

        assertEquals(listOf("https://new.test"), r.endpointsFor("0xa/one-1"))
        assertTrue(r.providersCheckedAt("0xa/one-1") > 0L)
    }

    @Test
    fun `a chain that answered nothing leaves the endpoints as they were`() {
        val r = registry()
        r.rememberEndpoints("0xa/one-1", listOf("https://old.test"))

        r.rememberProviders("0xa/one-1", emptyList())

        assertEquals(listOf("https://old.test"), r.endpointsFor("0xa/one-1"))
        assertTrue(r.providersCheckedAt("0xa/one-1") > 0L)
    }

    @Test
    fun `two channels sharing a tag are both returned`() {
        val r = registry()
        r.add(entry("0xa/one-1", "7f"))
        r.add(entry("0xa/two-1", "7f"))
        r.add(entry("0xa/three-1", "02"))

        assertEquals(listOf("0xa/one-1", "0xa/two-1"), r.entriesByTag("7f").map { it.streamId })
        assertEquals(1, r.entriesByTag("02").size)
        assertTrue(r.entriesByTag("ff").isEmpty())
    }

    @Test
    fun `a tag matches whatever case it arrives in`() {
        val r = registry()
        r.add(entry("0xa/one-1", "7F"))
        assertEquals(1, r.entriesByTag("7f").size)
    }

    @Test
    fun `endpoints survive a round trip and are kept per stream`() {
        val r = registry()
        r.rememberEndpoints("0xa/one-1", listOf("https://one.test", "https://two.test"))
        r.rememberEndpoints("0xa/two-1", listOf("https://other.test"))

        assertEquals(listOf("https://one.test", "https://two.test"), r.endpointsFor("0xa/one-1"))
        assertEquals(listOf("https://other.test"), r.endpointsFor("0xa/two-1"))
        assertTrue(r.endpointsFor("0xa/unknown-1").isEmpty())
    }

    @Test
    fun `endpoints are recorded before the channel is ever registered`() {
        val r = registry()
        r.rememberEndpoints("0xme/Pombo-DM-1", listOf("https://one.test"))

        assertTrue(r.all().isEmpty())
        assertEquals(listOf("https://one.test"), r.endpointsFor("0xme/Pombo-DM-1"))
    }

    @Test
    fun `a later resolution replaces the earlier one`() {
        val r = registry()
        r.rememberEndpoints("0xa/one-1", listOf("https://old.test"))
        r.rememberEndpoints("0xa/one-1", listOf("https://new.test"))

        assertEquals(listOf("https://new.test"), r.endpointsFor("0xa/one-1"))
    }

    @Test
    fun `an empty resolution never erases what is known`() {
        val r = registry()
        r.rememberEndpoints("0xa/one-1", listOf("https://one.test"))
        r.rememberEndpoints("0xa/one-1", emptyList())

        assertEquals(listOf("https://one.test"), r.endpointsFor("0xa/one-1"))
    }

    @Test
    fun `another account does not inherit these endpoints`() {
        registry(address = "0xme").rememberEndpoints("0xa/one-1", listOf("https://one.test"))

        assertTrue(registry(address = "0xother").endpointsFor("0xa/one-1").isEmpty())
    }
}
