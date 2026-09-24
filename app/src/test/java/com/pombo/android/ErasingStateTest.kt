package com.pombo.android

import com.pombo.android.core.StreamConstants
import io.mockk.coEvery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A message stays marked as erasing from the moment its erase starts until storage answers. */
class ErasingStateTest {

    private val me = com.pombo.android.core.EthereumSigner
        .address("0x4f3edf983ac636a65a842ce7c78d9aa706d3b113bce9c46f30d7d21715b23b1d").lowercase()
    private val streamId = "$me/room-1"
    private val h = ChannelManagerHarness(channels = listOf(ChannelManagerHarness.channel(streamId)))
    private val manager = h.manager

    @After fun tearDown() = h.stop()

    private val msg = UiMessage(
        id = "target", text = "spam", sender = "0x" + "ab".repeat(20), senderName = null,
        timestamp = 1_000L, mine = false, envelopeTs = 1_000L, seq = 0
    )

    /** Holds (or fails) every publish on the admin stream, where the hide before the erase goes. */
    private fun onAdminPublish(answer: suspend () -> Unit) {
        val admin = StreamConstants.deriveAdminId(streamId)
        coEvery { h.bridge.call(any(), any()) } coAnswers {
            if (secondArg<JSONObject>().optString("streamId") == admin) answer()
            JSONObject()
        }
    }

    private fun eraseInBackground(): Thread =
        Thread { runCatching { runBlocking { manager.eraseMessage("target") } } }.also { it.start() }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    @Test
    fun `the message is erasing while the erase runs and stops when it ends`() {
        manager.openChannel(streamId)
        manager.mergeMessages(listOf(msg))
        val held = CompletableDeferred<Unit>()
        onAdminPublish { held.await() }

        val erase = eraseInBackground()
        awaitUntil { "target" in manager.erasingIds.value }
        assertTrue("target" in manager.erasingIds.value)

        held.complete(Unit)
        erase.join(3_000)
        assertEquals(emptySet<String>(), manager.erasingIds.value)
    }

    @Test
    fun `a failed erase stops showing as erasing`() {
        manager.openChannel(streamId)
        manager.mergeMessages(listOf(msg))
        onAdminPublish { throw IllegalStateException("publish refused") }

        val error = runCatching { runBlocking { manager.eraseMessage("target") } }.exceptionOrNull()

        assertTrue(error != null)
        assertEquals(emptySet<String>(), manager.erasingIds.value)
    }
}
