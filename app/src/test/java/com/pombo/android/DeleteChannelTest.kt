package com.pombo.android

import io.mockk.coEvery
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On-chain channel deletion, one transaction per stream.
 *
 * The outcome that matters is the partial one: a run that only got through
 * some of the streams used to drop the channel from the device anyway, and
 * with it the only handle for deleting the rest.
 */
class DeleteChannelTest {

    private val h = ChannelManagerHarness(
        channels = listOf(
            ChannelManagerHarness.channel(
                "${com.pombo.android.core.EthereumSigner.address(
                    "0x4f3edf983ac636a65a842ce7c78d9aa706d3b113bce9c46f30d7d21715b23b1d"
                ).lowercase()}/room-1"
            )
        )
    )
    private val manager = h.manager
    private val streamId = "${h.me}/room-1"

    @After fun tearDown() = h.stop()

    private fun deletesExcept(vararg refused: String) {
        coEvery { h.bridge.call(eq("deleteStream"), any(), any()) } answers {
            val id = (secondArg<JSONObject>()).optString("streamId")
            if (refused.any { id.endsWith(it) }) throw IllegalStateException("RPC down")
            JSONObject().put("ok", true)
        }
    }

    @Test
    fun `a clean run deletes every stream and forgets the channel`() = runBlocking {
        deletesExcept()
        assertEquals(emptyList<String>(), manager.deleteChannel(streamId))
        assertTrue(manager.channels.value.none { it.messageStreamId == streamId })
    }

    /** The channel is the handle for the retry, so it has to survive. */
    @Test
    fun `a stream left standing keeps the channel on this device`() = runBlocking {
        deletesExcept("-3")
        val failed = manager.deleteChannel(streamId)
        assertEquals(listOf("${h.me}/room-3"), failed)
        assertTrue(manager.channels.value.any { it.messageStreamId == streamId })
    }

    /**
     * A run that dies halfway should leave a channel that still opens, so the
     * message stream is deleted after the rest.
     */
    @Test
    fun `the message stream is deleted last`() = runBlocking {
        val order = mutableListOf<String>()
        coEvery { h.bridge.call(eq("deleteStream"), any(), any()) } answers {
            order += (secondArg<JSONObject>()).optString("streamId")
            JSONObject().put("ok", true)
        }
        manager.deleteChannel(streamId)
        assertEquals(streamId, order.last())
    }
}
