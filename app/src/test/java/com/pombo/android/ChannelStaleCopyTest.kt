package com.pombo.android

import io.mockk.coEvery
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * An edit that waits on the chain lands on the record as it is when the call
 * returns. A save stamps every field that differs from the stored record, so
 * an edit built on the copy opened before the call would revert, on every
 * device, whatever a sync import brought in meanwhile.
 */
class ChannelStaleCopyTest {

    private val h = ChannelManagerHarness()
    private val member = "0x00000000000000000000000000000000000000b1"

    @After
    fun tearDown() = h.stop()

    @Test
    fun `adding a member keeps what a sync import brought in while the grants ran`() {
        val open = ChannelManagerHarness.channel("${h.me}/c0ffee-1")
        h.manager.replaceChannels(listOf(open))
        h.manager._current.value = open
        var imported = false
        coEvery { h.bridge.call("setPermissions", any(), any()) } answers {
            if (!imported) {
                imported = true
                h.manager.replaceChannels(
                    listOf(open.copy(name = "From another device", fieldTs = mapOf("name" to 5000L)))
                )
            }
            JSONObject()
        }

        runBlocking { h.manager.addMember(member) }

        val stored = h.manager._channels.value.single()
        assertEquals("From another device", stored.name)
        assertEquals(5000L, stored.fieldTs["name"])
        assertEquals(listOf(member), stored.members)
        assertEquals(stored, h.manager._current.value)
    }
}
