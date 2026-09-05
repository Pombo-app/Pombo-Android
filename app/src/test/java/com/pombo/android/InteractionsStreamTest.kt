package com.pombo.android

import com.pombo.android.core.StreamConstants
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reactions arriving live on the interactions stream (-5).
 *
 * The funnel used to accept the -5 only on gated channels, so a reaction in a
 * public or read-only channel was dropped where the channel is resolved and
 * never reached the map — invisible until a reopen read it back from storage,
 * which is the shape of bug that unit tests exist to stop coming back.
 */
class InteractionsStreamTest {

    private val owner = "0xowner"
    private val streamId = "$owner/room-1"
    private val interactionsId = StreamConstants.deriveInteractionsId(streamId)
    private val peer = "0xpeer"

    private val room = ChannelManagerHarness.channel(streamId)
        .copy(interactionsStreamId = interactionsId)

    private val h = ChannelManagerHarness(channels = listOf(room))
    private val manager = h.manager

    @After fun tearDown() = h.stop()

    private fun reaction(action: String, emoji: String = "🎉", messageId: String = "m1") =
        h.deliver(
            interactionsId, StreamConstants.P_REACTIONS,
            JSONObject()
                .put("type", "reaction")
                .put("action", action)
                .put("messageId", messageId)
                .put("emoji", emoji),
            peer
        )

    @Test
    fun `a reaction on the -5 of a public channel reaches the map`() {
        manager.openChannel(streamId)
        reaction("add")
        assertEquals(setOf(peer), manager.reactions.value["m1"]?.get("🎉"))
    }

    /** Removal travels the same way it did when reactions lived on the -1. */
    @Test
    fun `a removal on the -5 takes the reaction back off`() {
        manager.openChannel(streamId)
        reaction("add")
        // Assert the add first: without it the removal proves nothing, since
        // a funnel that drops both leaves the same empty map behind.
        assertEquals(setOf(peer), manager.reactions.value["m1"]?.get("🎉"))
        reaction("remove")
        assertNull(manager.reactions.value["m1"])
    }

    /** The -5 of another channel is not this channel's. */
    @Test
    fun `a reaction on someone else's -5 is ignored`() {
        manager.openChannel(streamId)
        h.deliver(
            "$owner/other-5", StreamConstants.P_REACTIONS,
            JSONObject().put("type", "reaction").put("action", "add")
                .put("messageId", "m1").put("emoji", "🎉"),
            peer
        )
        assertNull(manager.reactions.value["m1"])
    }
}
