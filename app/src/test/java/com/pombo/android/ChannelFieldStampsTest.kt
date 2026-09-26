package com.pombo.android

import com.pombo.android.data.Channel
import io.mockk.every
import io.mockk.slot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sync merges a channel record field by field on these stamps, so a
 * stamp on a field nobody changed here would let this device's stale value
 * win it on every other device, and a lost stamp would let an older copy
 * revert it.
 */
class ChannelFieldStampsTest {

    private val id = "0x00000000000000000000000000000000000000a1/c0ffee-1"
    private val h = ChannelManagerHarness(listOf(ChannelManagerHarness.channel(id)))
    private val saved = slot<List<Channel>>()

    init {
        every { h.store.save(capture(saved)) } returns Unit
    }

    @After
    fun tearDown() = h.stop()

    private fun stored() = saved.captured.single()

    @Test
    fun `a save stamps only the fields it changes`() {
        h.manager._channels.value = h.manager._channels.value.map { it.copy(name = "Renamed") }

        h.manager.saveChannels()

        assertEquals(setOf("name"), stored().fieldTs.keys)
        assertEquals(stored(), h.manager._channels.value.single())
    }

    @Test
    fun `a save that changes nothing stamps nothing`() {
        h.manager.saveChannels()

        assertTrue(stored().fieldTs.isEmpty())
    }

    @Test
    fun `what a sync pull brings in is not stamped, and its stamps are kept`() {
        val imported = h.manager._channels.value.single()
            .copy(name = "From another device", fieldTs = mapOf("name" to 5000L))
        h.manager.replaceChannels(listOf(imported))

        h.manager.saveChannels()

        assertEquals(mapOf("name" to 5000L), stored().fieldTs)
    }

    @Test
    fun `a copy that lost its stamps does not take them back from the stored record`() {
        val imported = h.manager._channels.value.single().copy(fieldTs = mapOf("name" to 5000L))
        h.manager.replaceChannels(listOf(imported))
        h.manager._channels.value = listOf(imported.copy(fieldTs = emptyMap(), description = "Edited"))

        h.manager.saveChannels()

        assertEquals(5000L, stored().fieldTs["name"])
        assertTrue("description" in stored().fieldTs)
    }

    @Test
    fun `a record created here carries no stamps`() {
        val created = ChannelManagerHarness.channel("0x00000000000000000000000000000000000000a1/beef-1")
        h.manager._channels.value = h.manager._channels.value + created

        h.manager.saveChannels()

        assertTrue(saved.captured.all { it.fieldTs.isEmpty() })
    }

    @Test
    fun `the stamps survive the stored and synced form`() {
        val channel = ChannelManagerHarness.channel(id).copy(fieldTs = mapOf("name" to 5000L, "storageDays" to 6000L))

        assertEquals(channel, Channel.fromJson(channel.toJson()))
    }
}
