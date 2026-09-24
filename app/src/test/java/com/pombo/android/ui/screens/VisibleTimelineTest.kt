package com.pombo.android.ui.screens

import com.pombo.android.ChannelManager
import com.pombo.android.UiMessage
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the chat draws of a timeline, before and after the open's loads finish. */
class VisibleTimelineTest {

    private val alice = "0x" + "a1".repeat(20)
    private val bob = "0x" + "b0".repeat(20)

    private fun msg(id: String, sender: String = alice, epoch: Int? = null) = UiMessage(
        id = id, text = id, sender = sender, senderName = null, timestamp = 0L, mine = false, epoch = epoch
    )

    private val all = listOf(msg("kept"), msg("new"), msg("hidden"), msg("banned", sender = bob))

    private fun restored(
        hidden: Set<String> = emptySet(),
        banSince: Map<String, Int?> = emptyMap()
    ) = ChannelManager.RestoredTimeline(setOf("kept", "hidden", "banned"), hidden, banSince)

    private fun ids(list: List<UiMessage>) = list.map { it.id }

    @Test
    fun `nothing shows while loading without a restored timeline`() {
        assertEquals(emptyList<String>(), ids(visibleTimeline(all, emptySet(), emptyMap(), false, true, null)))
    }

    @Test
    fun `while loading only the restored messages show`() {
        assertEquals(listOf("kept", "hidden", "banned"),
            ids(visibleTimeline(all, emptySet(), emptyMap(), false, true, restored())))
    }

    @Test
    fun `while loading what the restored timeline hid stays hidden though the live state lost it`() {
        val shown = visibleTimeline(all, emptySet(), emptyMap(), false, true,
            restored(hidden = setOf("hidden"), banSince = mapOf(bob to null)))

        assertEquals(listOf("kept"), ids(shown))
    }

    @Test
    fun `while loading the live state hides too`() {
        val shown = visibleTimeline(all, setOf("kept"), mapOf(bob to null), false, true, restored())

        assertEquals(listOf("hidden"), ids(shown))
    }

    @Test
    fun `a ban from an epoch on hides only what came after it`() {
        val timeline = listOf(msg("old", sender = bob, epoch = 1), msg("late", sender = bob, epoch = 3))
        val early = ChannelManager.RestoredTimeline(setOf("old", "late"), emptySet(), mapOf(bob to 2))

        assertEquals(listOf("old"), ids(visibleTimeline(timeline, emptySet(), emptyMap(), false, true, early)))
    }

    @Test
    fun `once loaded the restored timeline no longer decides`() {
        val shown = visibleTimeline(all, emptySet(), emptyMap(), false, false,
            restored(hidden = setOf("hidden"), banSince = mapOf(bob to null)))

        assertEquals(listOf("kept", "new", "hidden", "banned"), ids(shown))
    }

    @Test
    fun `a moderator still sees hidden messages`() {
        val shown = visibleTimeline(all, setOf("kept"), emptyMap(), true, true, restored(hidden = setOf("hidden")))

        assertEquals(listOf("kept", "hidden", "banned"), ids(shown))
    }
}
